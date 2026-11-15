package com.trace.recall.verification;

import com.trace.recall.common.Json;
import com.trace.recall.identity.IdentityResolutionService;
import com.trace.recall.lineage.LineageCompiler;
import com.trace.recall.quality.QuantityQualityEngine;
import com.trace.recall.release.ReleaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核验编排：锁定截止时间前的来源包快照（后续补录无法混入），
 * 依次执行身份解析、谱系编译、数量质量评估。
 */
@Service
public class VerificationService {

    private final JdbcTemplate jdbc;
    private final IdentityResolutionService identityService;
    private final LineageCompiler lineageCompiler;
    private final QuantityQualityEngine quantityEngine;
    private final ReleaseService releaseService;
    private final String currentRuleVersion;

    public VerificationService(JdbcTemplate jdbc, IdentityResolutionService identityService,
                               LineageCompiler lineageCompiler, QuantityQualityEngine quantityEngine,
                               ReleaseService releaseService,
                               @Value("${app.rule.current-rule-version}") String currentRuleVersion) {
        this.jdbc = jdbc;
        this.identityService = identityService;
        this.lineageCompiler = lineageCompiler;
        this.quantityEngine = quantityEngine;
        this.releaseService = releaseService;
        this.currentRuleVersion = currentRuleVersion;
    }

    @Transactional
    public Map<String, Object> createRun(String inspectionVersion, String cutoffText, String createdBy) {
        OffsetDateTime cutoff = OffsetDateTime.parse(cutoffText);
        List<Map<String, Object>> snapshot = jdbc.query(
                "SELECT id, package_code, source_system, source_revision, payload_hash, parse_rule_version, "
                        + "parse_status, received_at FROM source_package WHERE received_at <= ? ORDER BY received_at",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("packageCode", rs.getString("package_code"));
                    m.put("sourceSystem", rs.getString("source_system"));
                    m.put("sourceRevision", rs.getString("source_revision"));
                    m.put("payloadHash", rs.getString("payload_hash"));
                    m.put("parseRuleVersion", rs.getString("parse_rule_version"));
                    m.put("parseStatus", rs.getString("parse_status"));
                    m.put("receivedAt", rs.getObject("received_at").toString());
                    return m;
                }, cutoff);
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("截止时间前没有任何来源包，无法核验");
        }
        boolean versionInSnapshot = snapshot.stream()
                .anyMatch(p -> inspectionVersion.equals(p.get("sourceRevision"))
                        && "QC_LAB".equals(p.get("sourceSystem")));
        if (!versionInSnapshot) {
            throw new IllegalArgumentException("检验更正版本 " + inspectionVersion + " 不在截止时间快照内，不能用于本次核验");
        }

        Long seq = jdbc.queryForObject("SELECT COALESCE(max(id),0)+1 FROM verification_run", Long.class);
        String runCode = String.format("RUN-%03d", seq);
        List<Long> pkgIds = snapshot.stream().map(p -> (Long) p.get("id")).toList();
        Map<String, Object> snapshotJson = new LinkedHashMap<>();
        snapshotJson.put("cutoffAt", cutoff.toString());
        snapshotJson.put("packages", snapshot.stream().map(p -> {
            Map<String, Object> slim = new LinkedHashMap<>(p);
            slim.remove("id");
            return slim;
        }).toList());

        jdbc.update("INSERT INTO verification_run(run_code, inspection_correction_version, cutoff_at, rule_version, "
                        + "status, input_snapshot, created_by) VALUES (?,?,?,?,'LOCKED',?,?)",
                runCode, inspectionVersion, cutoff, currentRuleVersion, Json.jsonb(snapshotJson), createdBy);
        long runId = jdbc.queryForObject("SELECT id FROM verification_run WHERE run_code=?", Long.class, runCode);

        identityService.resolve(runId, pkgIds);

        List<LineageCompiler.Pkg> pkgMeta = new ArrayList<>();
        for (Map<String, Object> p : snapshot) {
            String affected = jdbc.query("SELECT affected_types FROM parse_rule WHERE version=?",
                    rs -> rs.next() ? rs.getString("affected_types") : "",
                    p.get("parseRuleVersion"));
            pkgMeta.add(new LineageCompiler.Pkg((Long) p.get("id"), (String) p.get("packageCode"),
                    (String) p.get("parseStatus"), (String) p.get("parseRuleVersion"), affected));
        }
        lineageCompiler.compile(runId, pkgMeta);

        List<String> unqualified = resolveUnqualified(inspectionVersion, pkgIds);
        quantityEngine.evaluate(runId, pkgIds, unqualified);

        return Map.of("runId", runId, "runCode", runCode);
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveUnqualified(String inspectionVersion, List<Long> pkgIds) {
        for (Long pkgId : pkgIds) {
            List<Map<String, Object>> qcFacts = jdbc.query(
                    "SELECT f.payload FROM raw_fact f JOIN source_package p ON f.package_id=p.id "
                            + "WHERE f.package_id=? AND f.fact_type='INSPECTION' AND p.source_revision=?",
                    (rs, i) -> Json.map(rs.getObject("payload")), pkgId, inspectionVersion);
            for (Map<String, Object> payload : qcFacts) {
                Object list = payload.get("unqualifiedBatches");
                if (list instanceof List<?> l) {
                    return l.stream().map(String::valueOf).toList();
                }
            }
        }
        throw new IllegalStateException("检验更正版本 " + inspectionVersion + " 缺少不合格批次结论");
    }

    public List<Map<String, Object>> listRuns() {
        return jdbc.query("SELECT id, run_code, inspection_correction_version, cutoff_at, rule_version, status, "
                + "input_snapshot, created_by, created_at FROM verification_run ORDER BY id", (rs, i) -> runSummary(rs));
    }

    public Map<String, Object> getRun(long runId) {
        Map<String, Object> run = jdbc.query("SELECT * FROM verification_run WHERE id=?", rs -> {
            if (!rs.next()) {
                throw new IllegalArgumentException("核验不存在: " + runId);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("runCode", rs.getString("run_code"));
            m.put("inspectionCorrectionVersion", rs.getString("inspection_correction_version"));
            m.put("cutoffAt", rs.getObject("cutoff_at").toString());
            m.put("ruleVersion", rs.getString("rule_version"));
            m.put("status", rs.getString("status"));
            m.put("inputSnapshot", Json.map(rs.getObject("input_snapshot")));
            m.put("createdBy", rs.getString("created_by"));
            return m;
        }, runId);

        List<Map<String, Object>> packages = jdbc.query(
                "SELECT package_code, source_system, source_revision, payload_hash, parse_rule_version, "
                        + "parse_status, received_at FROM source_package sp "
                        + "JOIN verification_run vr ON vr.id=? WHERE sp.received_at <= vr.cutoff_at ORDER BY sp.received_at",
                (rs, i) -> Map.of(
                        "packageCode", rs.getString("package_code"),
                        "sourceSystem", rs.getString("source_system"),
                        "sourceRevision", rs.getString("source_revision"),
                        "payloadHash", rs.getString("payload_hash"),
                        "parseRuleVersion", rs.getString("parse_rule_version"),
                        "parseStatus", rs.getString("parse_status"),
                        "receivedAt", rs.getObject("received_at").toString()), runId);

        List<Map<String, Object>> quantities = jdbc.query(
                "SELECT batch_code, confirmed_lower, pending_qty, possible_upper, unattributed_delta, unit, detail "
                        + "FROM quantity_state WHERE run_id=? ORDER BY batch_code",
                (rs, i) -> Map.of(
                        "batchCode", rs.getString("batch_code"),
                        "confirmedLower", rs.getBigDecimal("confirmed_lower"),
                        "pendingQty", rs.getBigDecimal("pending_qty"),
                        "possibleUpper", rs.getBigDecimal("possible_upper"),
                        "unattributedDelta", rs.getBigDecimal("unattributed_delta"),
                        "unit", rs.getString("unit"),
                        "detail", Json.map(rs.getObject("detail"))), runId);

        List<Map<String, Object>> gaps = jdbc.query(
                "SELECT id, gap_type, severity, subject_code, node_name, description, blocks_conclusion, evidence, status "
                        + "FROM evidence_gap WHERE run_id=? ORDER BY id",
                (rs, i) -> Map.of(
                        "id", rs.getLong("id"),
                        "gapType", rs.getString("gap_type"),
                        "severity", rs.getString("severity"),
                        "subjectCode", rs.getString("subject_code"),
                        "nodeName", rs.getString("node_name") == null ? "" : rs.getString("node_name"),
                        "description", rs.getString("description"),
                        "blocksConclusion", rs.getString("blocks_conclusion"),
                        "evidence", Json.list(rs.getObject("evidence")),
                        "status", rs.getString("status")), runId);

        List<Map<String, Object>> identityRecords = jdbc.query(
                "SELECT id, code_type, code_value, observed_batch_code, match_status "
                        + "FROM identity_record WHERE run_id=? ORDER BY id",
                (rs, i) -> {
                    long recordId = rs.getLong("id");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", recordId);
                    m.put("codeType", rs.getString("code_type"));
                    m.put("codeValue", rs.getString("code_value"));
                    m.put("observedBatchCode", rs.getString("observed_batch_code"));
                    m.put("matchStatus", rs.getString("match_status"));
                    m.put("candidates", jdbc.query(
                            "SELECT id, candidate_batch_code, confidence, ambiguous, evidence FROM identity_candidate "
                                    + "WHERE identity_record_id=? ORDER BY id",
                            (crs, ci) -> Map.of(
                                    "id", crs.getLong("id"),
                                    "candidateBatchCode", crs.getString("candidate_batch_code"),
                                    "confidence", crs.getString("confidence"),
                                    "ambiguous", crs.getBoolean("ambiguous"),
                                    "evidence", Json.list(crs.getObject("evidence"))), recordId));
                    return m;
                }, runId);

        List<Map<String, Object>> edges = jdbc.query(
                "SELECT edge_code, upstream_batch, downstream_batch, relation_type, source_fact_code, "
                        + "source_package_code, valid_from, confidence_status, review_required, block_reason, "
                        + "parse_rule_version, quantity, unit FROM lineage_edge WHERE run_id=? ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("edgeCode", rs.getString("edge_code"));
                    m.put("upstreamBatch", rs.getString("upstream_batch"));
                    m.put("upstream", rs.getString("upstream_batch") != null);
                    m.put("downstreamBatch", rs.getString("downstream_batch"));
                    m.put("relationType", rs.getString("relation_type"));
                    m.put("sourceFactCode", rs.getString("source_fact_code"));
                    m.put("sourcePackageCode", rs.getString("source_package_code"));
                    m.put("validFrom", rs.getObject("valid_from").toString());
                    m.put("confidenceStatus", rs.getString("confidence_status"));
                    m.put("reviewRequired", rs.getBoolean("review_required"));
                    m.put("blockReason", rs.getString("block_reason"));
                    m.put("parseRuleVersion", rs.getString("parse_rule_version"));
                    m.put("quantity", rs.getBigDecimal("quantity"));
                    m.put("unit", rs.getString("unit"));
                    return m;
                }, runId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("packages", packages);
        result.put("quantities", quantities);
        result.put("gaps", gaps);
        result.put("identityRecords", identityRecords);
        result.put("edges", edges);
        return result;
    }

    /** 批次全链路证据钻取。 */
    public Map<String, Object> getTrace(long runId, String batchCode) {
        Map<String, Object> batchMaster = jdbc.query(
                "SELECT batch_code, batch_type, product_code, product_name, quantity, unit FROM batch_master WHERE batch_code=?",
                rs -> rs.next() ? Map.of(
                        "batchCode", rs.getString("batch_code"),
                        "batchType", rs.getString("batch_type"),
                        "productCode", rs.getString("product_code") == null ? "" : rs.getString("product_code"),
                        "productName", rs.getString("product_name") == null ? "" : rs.getString("product_name"),
                        "quantity", rs.getBigDecimal("quantity"),
                        "unit", rs.getString("unit")) : Map.of(), batchCode);

        Map<String, Object> quantity = jdbc.query(
                "SELECT confirmed_lower, pending_qty, possible_upper, unattributed_delta, unit, detail "
                        + "FROM quantity_state WHERE run_id=? AND batch_code=?",
                rs -> rs.next() ? Map.of(
                        "confirmedLower", rs.getBigDecimal("confirmed_lower"),
                        "pendingQty", rs.getBigDecimal("pending_qty"),
                        "possibleUpper", rs.getBigDecimal("possible_upper"),
                        "unattributedDelta", rs.getBigDecimal("unattributed_delta"),
                        "unit", rs.getString("unit"),
                        "detail", Json.map(rs.getObject("detail"))) : null, runId, batchCode);

        List<Map<String, Object>> edges = jdbc.query(
                "SELECT * FROM lineage_edge WHERE run_id=? AND (upstream_batch=? OR downstream_batch=?) ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("edgeCode", rs.getString("edge_code"));
                    m.put("relationType", rs.getString("relation_type"));
                    m.put("upstreamBatch", rs.getString("upstream_batch"));
                    m.put("downstreamBatch", rs.getString("downstream_batch"));
                    m.put("quantity", rs.getBigDecimal("quantity"));
                    m.put("unit", rs.getString("unit"));
                    m.put("sourceFactCode", rs.getString("source_fact_code"));
                    m.put("sourcePackageCode", rs.getString("source_package_code"));
                    m.put("validFrom", rs.getObject("valid_from").toString());
                    m.put("confidenceStatus", rs.getString("confidence_status"));
                    m.put("parseRuleVersion", rs.getString("parse_rule_version"));
                    m.put("blockReason", rs.getString("block_reason") == null ? "" : rs.getString("block_reason"));
                    return m;
                },
                runId, batchCode, batchCode);

        List<Map<String, Object>> identityRecords = jdbc.query(
                "SELECT id, code_type, code_value, observed_batch_code, match_status FROM identity_record "
                        + "WHERE run_id=? AND observed_batch_code=? ORDER BY id",
                (rs, i) -> {
                    long recordId = rs.getLong("id");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", recordId);
                    m.put("codeType", rs.getString("code_type"));
                    m.put("codeValue", rs.getString("code_value"));
                    m.put("matchStatus", rs.getString("match_status"));
                    m.put("candidates", jdbc.query(
                            "SELECT candidate_batch_code, confidence, ambiguous FROM identity_candidate WHERE identity_record_id=?",
                            (crs, ci) -> Map.of(
                                    "candidateBatchCode", crs.getString("candidate_batch_code"),
                                    "confidence", crs.getString("confidence"),
                                    "ambiguous", crs.getBoolean("ambiguous")), recordId));
                    return m;
                }, runId, batchCode);

        List<Map<String, Object>> gaps = jdbc.query(
                "SELECT gap_type, subject_code, description, blocks_conclusion FROM evidence_gap "
                        + "WHERE run_id=? AND subject_code=? ORDER BY id",
                (rs, i) -> Map.of(
                        "gapType", rs.getString("gap_type"),
                        "subject", rs.getString("subject_code"),
                        "subjectCode", rs.getString("subject_code"),
                        "description", rs.getString("description"),
                        "blocksConclusion", rs.getString("blocks_conclusion")), runId, batchCode);

        List<String> packageCodes = jdbc.query(
                "SELECT DISTINCT source_package_code FROM lineage_edge WHERE run_id=? "
                        + "AND (upstream_batch=? OR downstream_batch=?)",
                (rs, i) -> rs.getString(1), runId, batchCode, batchCode);
        List<Map<String, Object>> rawPackages = packageCodes.isEmpty() ? List.of()
                : jdbc.query("SELECT package_code, source_system, source_revision, payload_hash, parse_rule_version, "
                        + "parse_status, received_at, payload FROM source_package WHERE package_code IN ("
                        + placeholders(packageCodes.size()) + ") ORDER BY received_at",
                (rs, i) -> Map.of(
                        "packageCode", rs.getString("package_code"),
                        "sourceSystem", rs.getString("source_system"),
                        "sourceRevision", rs.getString("source_revision"),
                        "payloadHash", rs.getString("payload_hash"),
                        "parseRuleVersion", rs.getString("parse_rule_version"),
                        "parseStatus", rs.getString("parse_status"),
                        "receivedAt", rs.getObject("received_at").toString(),
                        "payload", Json.map(rs.getObject("payload"))),
                packageCodes.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchMaster", batchMaster);
        result.put("quantity", quantity);
        result.put("edges", edges);
        result.put("identityRecords", identityRecords);
        result.put("gaps", gaps);
        result.put("rawPackages", rawPackages);
        return result;
    }

    @Transactional
    public Map<String, Object> publish(long runId) {
        return releaseService.publish(runId);
    }

    private Map<String, Object> runSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("runCode", rs.getString("run_code"));
        m.put("inspectionCorrectionVersion", rs.getString("inspection_correction_version"));
        m.put("cutoffAt", rs.getObject("cutoff_at").toString());
        m.put("ruleVersion", rs.getString("rule_version"));
        m.put("status", rs.getString("status"));
        m.put("inputSnapshot", Json.map(rs.getObject("input_snapshot")));
        m.put("createdBy", rs.getString("created_by"));
        return m;
    }

    private String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
