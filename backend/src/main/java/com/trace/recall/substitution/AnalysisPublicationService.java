package com.trace.recall.substitution;

import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布视图：按批次范围的生效日期（分析请求日期）选择候选条目版本，
 * 输出可用目录条目、受限原因、证据完整度与未决冲突，并固化全量下钻快照。
 * 包装层级变更时严格按日期命中历史版本，不用当前包装覆盖历史声明。
 */
@Service
public class AnalysisPublicationService {

    private final JdbcTemplate jdbc;

    public AnalysisPublicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createRequest(String scopeCode, String analysisDateText, String createdBy) {
        OffsetDateTime analysisDate = OffsetDateTime.parse(analysisDateText);
        Long scopeId = jdbc.query("SELECT id FROM batch_scope WHERE scope_code=?",
                rs -> rs.next() ? rs.getLong("id") : null, scopeCode);
        if (scopeId == null) {
            throw new IllegalArgumentException("批次范围不存在: " + scopeCode);
        }
        long seq = jdbc.queryForObject("SELECT COALESCE(max(id),0)+1 FROM analysis_request", Long.class);
        String requestCode = String.format("AR-%03d", seq);
        jdbc.update("INSERT INTO analysis_request(request_code, scope_id, analysis_date, status, created_by) "
                + "VALUES (?,?,?,'PUBLISHED',?)", requestCode, scopeId, analysisDate, createdBy);
        long requestId = jdbc.queryForObject("SELECT id FROM analysis_request WHERE request_code=?",
                Long.class, requestCode);

        List<Map<String, Object>> targets = jdbc.query(
                "SELECT target_product_code, target_product_name, target_spec FROM scope_target WHERE scope_id=? ORDER BY id",
                (rs, i) -> Map.of("targetProductCode", rs.getString("target_product_code"),
                        "targetProductName", rs.getString("target_product_name"),
                        "targetSpec", Json.map(rs.getObject("target_spec"))), scopeId);
        for (Map<String, Object> target : targets) {
            publishItem(requestId, str(target.get("targetProductCode")), analysisDate);
        }
        return Map.of("requestId", requestId, "requestCode", requestCode,
                "itemCount", targets.size());
    }

    private void publishItem(long requestId, String targetCode, OffsetDateTime analysisDate) {
        // 候选：条目声明指向该目标产品，且条目版本生效期间覆盖分析日期
        List<Map<String, Object>> candidates = jdbc.query(
                "SELECT cev.id AS version_id, ce.entry_code, ce.product_name, ss.id AS statement_id, ss.verdict, "
                        + "ss.review_status, ss.evidence_completeness, cev.effective_from, cev.effective_to, cev.version_no "
                        + "FROM catalog_entry ce "
                        + "JOIN catalog_entry_version cev ON cev.entry_id=ce.id "
                        + "JOIN suitability_statement ss ON ss.entry_version_id=cev.id "
                        + "WHERE ce.target_product_code=? "
                        + "AND cev.effective_from <= ? AND (cev.effective_to IS NULL OR cev.effective_to >= ?) "
                        + "ORDER BY cev.version_no DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("versionId", rs.getLong("version_id"));
                    m.put("entryCode", rs.getString("entry_code"));
                    m.put("productName", rs.getString("product_name"));
                    m.put("statementId", rs.getLong("statement_id"));
                    m.put("verdict", rs.getString("verdict"));
                    m.put("reviewStatus", rs.getString("review_status"));
                    m.put("evidenceCompleteness", rs.getBigDecimal("evidence_completeness"));
                    m.put("versionNo", rs.getInt("version_no"));
                    return m;
                }, targetCode, analysisDate, analysisDate);

        if (candidates.isEmpty()) {
            Map<String, Object> emptyDrill = new LinkedHashMap<>();
            emptyDrill.put("targetProductCode", targetCode);
            emptyDrill.put("candidate", null);
            jdbc.update("INSERT INTO analysis_published_item(request_id, target_product_code, effective_verdict, "
                    + "restriction_reasons, evidence_completeness, unresolved_conflicts, drilldown) "
                    + "VALUES (?,?, 'NOT_COMPARABLE', ?, 0, ?, ?::jsonb)",
                    requestId, targetCode,
                    Json.jsonb(List.of("分析日期内没有生效中的目录条目版本")),
                    Json.jsonb(List.of()), Json.jsonb(emptyDrill));
            return;
        }
        // 同日期多候选（同名异剂型）：不得归并，取最保守结论并在受限原因中保留全部候选
        Map<String, Object> chosen = candidates.get(0);
        String effectiveVerdict = conservativeVerdict(candidates);
        long versionId = ((Number) chosen.get("versionId")).longValue();
        long statementId = ((Number) chosen.get("statementId")).longValue();

        List<Map<String, Object>> openConflicts = jdbc.query(
                "SELECT conflict_code, conflict_type, severity, description, status FROM catalog_conflict "
                        + "WHERE entry_version_id=? AND status='OPEN' ORDER BY severity DESC, id",
                (rs, i) -> Map.of("conflictCode", rs.getString("conflict_code"),
                        "conflictType", rs.getString("conflict_type"),
                        "severity", rs.getString("severity"),
                        "description", rs.getString("description"),
                        "status", rs.getString("status")), versionId);

        List<String> restrictionReasons = new ArrayList<>();
        for (Map<String, Object> c : openConflicts) {
            restrictionReasons.add(str(c.get("conflictType")) + ": " + c.get("description"));
        }
        if (candidates.size() > 1) {
            restrictionReasons.add("SAME_NAME_DIFF_FORM: 分析日期内存在 " + candidates.size()
                    + " 个同名不同剂型候选版本，系统不归并，须按给药途径逐条判定");
        }

        Map<String, Object> drilldown = buildDrilldown(versionId, statementId, candidates, analysisDate);
        double completeness = candidates.stream()
                .mapToDouble(c -> ((Number) c.get("evidenceCompleteness")).doubleValue()).min().orElse(0);

        jdbc.update("INSERT INTO analysis_published_item(request_id, target_product_code, entry_code, entry_version_id, "
                        + "statement_id, effective_verdict, restriction_reasons, evidence_completeness, "
                        + "unresolved_conflicts, drilldown) VALUES (?,?,?,?,?,?,?,?,?,?)",
                requestId, targetCode, chosen.get("entryCode"), versionId, statementId, effectiveVerdict,
                Json.jsonb(restrictionReasons), completeness, Json.jsonb(openConflicts), Json.jsonb(drilldown));
    }

    /** 结论保守序：NOT_COMPARABLE > PENDING_REVIEW > CONDITIONALLY_COMPARABLE > COMPARABLE。 */
    private String conservativeVerdict(List<Map<String, Object>> candidates) {
        int rank = 0;
        String picked = "COMPARABLE";
        for (Map<String, Object> c : candidates) {
            int r = switch (str(c.get("verdict"))) {
                case "NOT_COMPARABLE" -> 4;
                case "PENDING_REVIEW" -> 3;
                case "CONDITIONALLY_COMPARABLE" -> 2;
                default -> 1;
            };
            if (r > rank) {
                rank = r;
                picked = str(c.get("verdict"));
            }
        }
        return picked;
    }

    private Map<String, Object> buildDrilldown(long versionId, long statementId,
                                               List<Map<String, Object>> candidates, OffsetDateTime analysisDate) {
        Map<String, Object> drill = new LinkedHashMap<>();
        drill.put("analysisDate", analysisDate.toString());
        drill.put("candidateVersions", candidates);
        drill.put("sourceDocuments", jdbc.query(
                "SELECT DISTINCT d.doc_code, d.doc_type, d.source_name, d.source_revision, d.source_location, "
                        + "d.effective_from, d.effective_to, d.payload_hash "
                        + "FROM semantic_attribute sa JOIN catalog_document d ON d.id=sa.source_doc_id "
                        + "WHERE sa.entry_version_id=? ORDER BY d.doc_code",
                (rs, i) -> {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("docCode", rs.getString("doc_code"));
                    doc.put("docType", rs.getString("doc_type"));
                    doc.put("sourceName", rs.getString("source_name"));
                    doc.put("sourceRevision", rs.getString("source_revision"));
                    doc.put("sourceLocation", rs.getString("source_location"));
                    doc.put("effectiveFrom", rs.getObject("effective_from").toString());
                    doc.put("effectiveTo", rs.getObject("effective_to") == null
                            ? null : rs.getObject("effective_to").toString());
                    doc.put("payloadHash", rs.getString("payload_hash"));
                    return doc;
                }, versionId));
        drill.put("controlledAttributes", jdbc.query(
                "SELECT attr_name, normalized_value, raw_value, convertible, sa.source_location, "
                        + "d.doc_code AS source_doc_code, attr_detail "
                        + "FROM semantic_attribute sa JOIN catalog_document d ON d.id=sa.source_doc_id "
                        + "WHERE sa.entry_version_id=? ORDER BY sa.id",
                (rs, i) -> Map.of("attrName", rs.getString("attr_name"),
                        "normalizedValue", rs.getString("normalized_value"),
                        "rawValue", rs.getString("raw_value"),
                        "convertible", rs.getBoolean("convertible"),
                        "sourceLocation", rs.getString("source_location"),
                        "sourceDocCode", rs.getString("source_doc_code"),
                        "detail", Json.map(rs.getObject("attr_detail"))), versionId));
        drill.put("statement", jdbc.queryForObject(
                "SELECT statement_code, verdict, review_status, basis, conditions, applicability_scope, "
                        + "evidence_completeness, reviewer, review_note, reviewed_at FROM suitability_statement WHERE id=?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("statementCode", rs.getString("statement_code"));
                    m.put("verdict", rs.getString("verdict"));
                    m.put("reviewStatus", rs.getString("review_status"));
                    m.put("basis", Json.list(rs.getObject("basis")));
                    m.put("conditions", Json.list(rs.getObject("conditions")));
                    m.put("applicabilityScope", rs.getString("applicability_scope"));
                    m.put("evidenceCompleteness", rs.getBigDecimal("evidence_completeness"));
                    m.put("reviewer", rs.getString("reviewer"));
                    m.put("reviewNote", rs.getString("review_note"));
                    m.put("reviewedAt", rs.getObject("reviewed_at") == null ? null
                            : rs.getObject("reviewed_at").toString());
                    return m;
                }, statementId));
        drill.put("counterexampleTrials", jdbc.query(
                "SELECT trial_code, scenario_type, input_summary, expected_boundary, result, "
                        + "refutes_statement, blocks_approval, evidence FROM counterexample_trial WHERE statement_id=? ORDER BY id",
                (rs, i) -> Map.of("trialCode", rs.getString("trial_code"),
                        "scenarioType", rs.getString("scenario_type"),
                        "inputSummary", rs.getString("input_summary"),
                        "expectedBoundary", rs.getString("expected_boundary"),
                        "result", rs.getString("result"),
                        "refutesStatement", rs.getBoolean("refutes_statement"),
                        "blocksApproval", rs.getBoolean("blocks_approval"),
                        "evidence", Json.map(rs.getObject("evidence"))), statementId));
        return drill;
    }

    public List<Map<String, Object>> listRequests() {
        return jdbc.query(
                "SELECT ar.id, ar.request_code, bs.scope_code, ar.analysis_date, ar.status, ar.created_by, ar.created_at, "
                        + "(SELECT count(*) FROM analysis_published_item pi WHERE pi.request_id=ar.id) AS item_count "
                        + "FROM analysis_request ar JOIN batch_scope bs ON bs.id=ar.scope_id ORDER BY ar.id",
                (rs, i) -> Map.of("id", rs.getLong("id"),
                        "requestCode", rs.getString("request_code"),
                        "scopeCode", rs.getString("scope_code"),
                        "analysisDate", rs.getObject("analysis_date").toString(),
                        "status", rs.getString("status"),
                        "createdBy", rs.getString("created_by"),
                        "itemCount", rs.getInt("item_count")));
    }

    public Map<String, Object> getRequest(long requestId) {
        Map<String, Object> header = jdbc.query(
                "SELECT ar.id, ar.request_code, bs.scope_code, bs.scope_name, ar.analysis_date, ar.status, ar.created_by "
                        + "FROM analysis_request ar JOIN batch_scope bs ON bs.id=ar.scope_id WHERE ar.id=?",
                rs -> {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("分析请求不存在: " + requestId);
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("requestCode", rs.getString("request_code"));
                    m.put("scopeCode", rs.getString("scope_code"));
                    m.put("scopeName", rs.getString("scope_name"));
                    m.put("analysisDate", rs.getObject("analysis_date").toString());
                    m.put("status", rs.getString("status"));
                    m.put("createdBy", rs.getString("created_by"));
                    return m;
                }, requestId);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT * FROM analysis_published_item WHERE request_id=? ORDER BY target_product_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("targetProductCode", rs.getString("target_product_code"));
                    m.put("entryCode", rs.getString("entry_code"));
                    m.put("entryVersionId", rs.getObject("entry_version_id"));
                    m.put("statementId", rs.getObject("statement_id"));
                    m.put("effectiveVerdict", rs.getString("effective_verdict"));
                    m.put("restrictionReasons", Json.list(rs.getObject("restriction_reasons")));
                    m.put("evidenceCompleteness", rs.getBigDecimal("evidence_completeness"));
                    m.put("unresolvedConflicts", Json.list(rs.getObject("unresolved_conflicts")));
                    m.put("drilldown", Json.map(rs.getObject("drilldown")));
                    return m;
                }, requestId);
        return Map.of("request", header, "items", items);
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
