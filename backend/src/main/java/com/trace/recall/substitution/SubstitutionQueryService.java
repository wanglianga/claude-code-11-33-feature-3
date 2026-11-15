package com.trace.recall.substitution;

import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 替代目录域只读聚合：受理文档、条目版本与受控属性、声明册、矛盾与反例的工作台视图。
 */
@Service
public class SubstitutionQueryService {

    private final JdbcTemplate jdbc;

    public SubstitutionQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listScopes() {
        return jdbc.query("SELECT * FROM batch_scope ORDER BY id", (rs, i) -> {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("scopeCode", rs.getString("scope_code"));
            scope.put("scopeName", rs.getString("scope_name"));
            scope.put("effectiveFrom", rs.getObject("effective_from").toString());
            scope.put("effectiveTo", rs.getObject("effective_to") == null
                    ? null : rs.getObject("effective_to").toString());
            scope.put("remark", rs.getString("remark") == null ? "" : rs.getString("remark"));
            scope.put("targets", jdbc.query("SELECT target_product_code, target_product_name, target_spec "
                            + "FROM scope_target WHERE scope_id=? ORDER BY id",
                    (trs, ti) -> Map.of("targetProductCode", trs.getString("target_product_code"),
                            "targetProductName", trs.getString("target_product_name"),
                            "targetSpec", Json.map(trs.getObject("target_spec"))),
                    rs.getLong("id")));
            return scope;
        });
    }

    public List<Map<String, Object>> listDocuments() {
        return jdbc.query("SELECT * FROM catalog_document ORDER BY id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docCode", rs.getString("doc_code"));
            m.put("docType", rs.getString("doc_type"));
            m.put("sourceName", rs.getString("source_name"));
            m.put("sourceRevision", rs.getString("source_revision"));
            m.put("sourceLocation", rs.getString("source_location"));
            m.put("payloadHash", rs.getString("payload_hash"));
            m.put("receivedAt", rs.getObject("received_at").toString());
            m.put("effectiveFrom", rs.getObject("effective_from").toString());
            m.put("effectiveTo", rs.getObject("effective_to") == null ? null
                    : rs.getObject("effective_to").toString());
            m.put("supersedesDocCode", rs.getString("supersedes_doc_code"));
            m.put("rawPayload", Json.map(rs.getObject("raw_payload")));
            return m;
        });
    }

    public List<Map<String, Object>> listEntries() {
        return jdbc.query("SELECT * FROM catalog_entry ORDER BY id", (rs, i) -> {
            long entryId = rs.getLong("id");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryCode", rs.getString("entry_code"));
            m.put("productName", rs.getString("product_name"));
            m.put("manufacturer", rs.getString("manufacturer"));
            m.put("targetProductCode", rs.getString("target_product_code"));
            m.put("versions", jdbc.query(
                    "SELECT cev.id, cev.version_no, cev.source_location, cev.effective_from, cev.effective_to, "
                            + "d.doc_code AS doc_code, d.doc_type AS doc_type, cev.raw_record "
                            + "FROM catalog_entry_version cev JOIN catalog_document d ON d.id=cev.doc_id "
                            + "WHERE cev.entry_id=? ORDER BY cev.version_no",
                    (vrs, vi) -> {
                        long versionId = vrs.getLong("id");
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("versionId", versionId);
                        v.put("versionNo", vrs.getInt("version_no"));
                        v.put("sourceLocation", vrs.getString("source_location"));
                        v.put("effectiveFrom", vrs.getObject("effective_from").toString());
                        v.put("effectiveTo", vrs.getObject("effective_to") == null ? null
                                : vrs.getObject("effective_to").toString());
                        v.put("docCode", vrs.getString("doc_code"));
                        v.put("docType", vrs.getString("doc_type"));
                        v.put("rawRecord", Json.map(vrs.getObject("raw_record")));
                        v.put("attributes", jdbc.query(
                                "SELECT sa.attr_name, sa.normalized_value, sa.raw_value, sa.convertible, "
                                        + "sa.attr_detail, sa.source_location, d.doc_code AS source_doc_code, d.doc_type "
                                        + "FROM semantic_attribute sa JOIN catalog_document d ON d.id=sa.source_doc_id "
                                        + "WHERE sa.entry_version_id=? ORDER BY sa.id",
                                (ars, ai) -> Map.of("attrName", ars.getString("attr_name"),
                                        "normalizedValue", ars.getString("normalized_value"),
                                        "rawValue", ars.getString("raw_value"),
                                        "convertible", ars.getBoolean("convertible"),
                                        "detail", Json.map(ars.getObject("attr_detail")),
                                        "sourceLocation", ars.getString("source_location"),
                                        "sourceDocCode", ars.getString("source_doc_code"),
                                        "sourceDocType", ars.getString("doc_type")), versionId));
                        return v;
                    }, entryId));
            return m;
        });
    }

    public List<Map<String, Object>> listStatements() {
        return jdbc.query("SELECT ss.*, ce.entry_code, ce.product_name FROM suitability_statement ss "
                + "JOIN catalog_entry_version cev ON cev.id=ss.entry_version_id "
                + "JOIN catalog_entry ce ON ce.id=cev.entry_id ORDER BY ss.id", (rs, i) -> {
            long statementId = rs.getLong("id");
            long versionId = rs.getLong("entry_version_id");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", statementId);
            m.put("statementCode", rs.getString("statement_code"));
            m.put("entryCode", rs.getString("entry_code"));
            m.put("productName", rs.getString("product_name"));
            m.put("targetProductCode", rs.getString("target_product_code"));
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
            m.put("conflicts", jdbc.query(
                    "SELECT conflict_code, conflict_type, severity, subject_attr, description, status, source_refs "
                            + "FROM catalog_conflict WHERE entry_version_id=? ORDER BY severity DESC, id",
                    (crs, ci) -> Map.of("conflictCode", crs.getString("conflict_code"),
                            "conflictType", crs.getString("conflict_type"),
                            "severity", crs.getString("severity"),
                            "subjectAttr", crs.getString("subject_attr"),
                            "description", crs.getString("description"),
                            "status", crs.getString("status"),
                            "sourceRefs", Json.list(crs.getObject("source_refs"))), versionId));
            m.put("trials", jdbc.query(
                    "SELECT trial_code, scenario_type, input_summary, expected_boundary, result, "
                            + "refutes_statement, blocks_approval, evidence FROM counterexample_trial "
                            + "WHERE statement_id=? ORDER BY id",
                    (trs, ti) -> Map.of("trialCode", trs.getString("trial_code"),
                            "scenarioType", trs.getString("scenario_type"),
                            "inputSummary", trs.getString("input_summary"),
                            "expectedBoundary", trs.getString("expected_boundary"),
                            "result", trs.getString("result"),
                            "refutesStatement", trs.getBoolean("refutes_statement"),
                            "blocksApproval", trs.getBoolean("blocks_approval"),
                            "evidence", Json.map(trs.getObject("evidence"))), statementId));
            return m;
        });
    }

    public List<Map<String, Object>> listConflicts() {
        return jdbc.query("SELECT cc.*, ce.entry_code FROM catalog_conflict cc "
                + "LEFT JOIN catalog_entry ce ON ce.id=cc.entry_id ORDER BY cc.severity DESC, cc.id",
                (rs, i) -> Map.of("conflictCode", rs.getString("conflict_code"),
                        "conflictType", rs.getString("conflict_type"),
                        "severity", rs.getString("severity"),
                        "entryCode", rs.getString("entry_code") == null ? "" : rs.getString("entry_code"),
                        "subjectAttr", rs.getString("subject_attr") == null ? "" : rs.getString("subject_attr"),
                        "description", rs.getString("description"),
                        "sourceRefs", Json.list(rs.getObject("source_refs")),
                        "status", rs.getString("status"),
                        "resolution", rs.getString("resolution") == null ? "" : rs.getString("resolution")));
    }

    /**
     * 人工审核：只有不存在阻断反例与未决阻断级矛盾时才允许通过；驳回始终允许。
     * 审核决定只影响之后新发起的分析请求，已发布视图保持不可变。
     */
    public Map<String, Object> reviewStatement(long statementId, String decision, String reviewer, String note) {
        Map<String, Object> st = jdbc.queryForMap("SELECT * FROM suitability_statement WHERE id=?", statementId);
        long versionId = ((Number) st.get("entry_version_id")).longValue();
        Long blockingTrials = jdbc.queryForObject(
                "SELECT count(*) FROM counterexample_trial WHERE statement_id=? AND blocks_approval=true",
                Long.class, statementId);
        Long openBlockers = jdbc.queryForObject(
                "SELECT count(*) FROM catalog_conflict WHERE entry_version_id=? AND severity='BLOCKER' AND status='OPEN'",
                Long.class, versionId);
        if ("APPROVE".equals(decision)) {
            if (blockingTrials != null && blockingTrials > 0) {
                throw new IllegalStateException("存在 " + blockingTrials + " 条阻断反例，声明不能通过审核，请先补证或驳回");
            }
            if (openBlockers != null && openBlockers > 0) {
                throw new IllegalStateException("存在 " + openBlockers + " 个未决阻断级矛盾，声明不能通过审核");
            }
            jdbc.update("UPDATE suitability_statement SET verdict='COMPARABLE', review_status='APPROVED', "
                    + "reviewer=?, review_note=?, reviewed_at=? WHERE id=?",
                    reviewer, note, OffsetDateTime.now(), statementId);
        } else if ("REJECT".equals(decision)) {
            jdbc.update("UPDATE suitability_statement SET verdict='NOT_COMPARABLE', review_status='REJECTED', "
                    + "reviewer=?, review_note=?, reviewed_at=? WHERE id=?",
                    reviewer, note, OffsetDateTime.now(), statementId);
        } else {
            throw new IllegalArgumentException("审核决定只支持 APPROVE / REJECT");
        }
        return Map.of("statementId", statementId, "decision", decision);
    }
}
