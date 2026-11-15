package com.trace.recall.release;

import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 范围发布器：来源断言与发布范围分别版本化。
 * 检验更正只生成新候选版本，旧版本置 SUPERSEDED 只读保留；差异按三类变化区分并可混合。
 * 对外发布项走字段白名单，原始 payload 与敏感联系人字段任何情况下都不输出。
 */
@Service
public class ReleaseService {

    private static final Set<String> EXTERNAL_ITEM_FIELDS = Set.of(
            "batchCode", "nodeName", "confirmedQty", "pendingQty", "possibleQty",
            "lowerBound", "upperBound", "unit", "evidenceGrade", "traceLink", "conflictFlag");

    private final JdbcTemplate jdbc;

    public ReleaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> publish(long runId) {
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM verification_run WHERE id=?", runId);
        String currentStatus = (String) run.get("status");
        if ("PUBLISHED".equals(currentStatus)) {
            throw new IllegalStateException("核验 " + runId + " 已发布，不能重复发布");
        }

        Long prevReleaseId = jdbc.query("SELECT id FROM release_version ORDER BY version_no DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong("id") : null);
        int versionNo = prevReleaseId == null ? 1
                : jdbc.queryForObject("SELECT max(version_no)+1 FROM release_version", Integer.class);
        Long seq = jdbc.queryForObject("SELECT COALESCE(max(id),0)+1 FROM release_version", Long.class);
        String releaseCode = String.format("REL-%03d", seq);
        String predecessor = prevReleaseId == null ? null
                : jdbc.queryForObject("SELECT release_code FROM release_version WHERE id=?", String.class, prevReleaseId);

        List<Map<String, Object>> diffDetail = new ArrayList<>();
        String diffType = "NONE";
        if (prevReleaseId != null) {
            diffType = buildDiff(runId, prevReleaseId, diffDetail);
        }

        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("correctionVersion", run.get("inspection_correction_version"));
        basis.put("ruleVersion", run.get("rule_version"));
        basis.put("packages", Json.map(run.get("input_snapshot")).get("packages"));

        List<Map<String, Object>> conclusions = prevReleaseId == null
                ? List.of() : buildAffectedConclusions(runId, prevReleaseId);

        jdbc.update("INSERT INTO release_version(release_code, version_no, run_id, status, predecessor_release_code, "
                        + "diff_type, diff_detail, basis, affected_conclusions) VALUES (?,?,?,'PUBLISHED',?,?,?,?,?)",
                releaseCode, versionNo, runId, predecessor, diffType,
                Json.jsonb(diffDetail), Json.jsonb(basis), Json.jsonb(conclusions));
        long releaseId = jdbc.queryForObject("SELECT id FROM release_version WHERE release_code=?",
                Long.class, releaseCode);

        if (prevReleaseId != null) {
            jdbc.update("UPDATE release_version SET status='SUPERSEDED' WHERE id=?", prevReleaseId);
        }
        jdbc.update("UPDATE verification_run SET status='PUBLISHED' WHERE id=?", runId);
        insertReleaseItems(releaseId, runId);

        return Map.of("releaseId", releaseId, "releaseCode", releaseCode, "versionNo", versionNo);
    }

    private String buildDiff(long runId, long prevReleaseId, List<Map<String, Object>> diffDetail) {
        Map<String, Object> current = jdbc.queryForMap("SELECT * FROM verification_run WHERE id=?", runId);
        Long prevRunId = jdbc.queryForObject("SELECT run_id FROM release_version WHERE id=?", Long.class, prevReleaseId);
        Map<String, Object> previous = jdbc.queryForMap("SELECT * FROM verification_run WHERE id=?", prevRunId);
        List<String> types = new ArrayList<>();

        if (!current.get("inspection_correction_version").equals(previous.get("inspection_correction_version"))) {
            types.add("INSPECTION_CHANGED");
            diffDetail.add(Map.of("type", "INSPECTION_CHANGED",
                    "description", "核验选择的检验更正版本不同",
                    "from", previous.get("inspection_correction_version"),
                    "to", current.get("inspection_correction_version")));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> prevPkgs = (List<Map<String, Object>>) Json.map(previous.get("input_snapshot")).get("packages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> curPkgs = (List<Map<String, Object>>) Json.map(current.get("input_snapshot")).get("packages");
        Set<String> prevCodes = new java.util.HashSet<>(prevPkgs.stream().map(p -> str(p.get("packageCode"))).toList());
        List<String> newLogistics = curPkgs.stream()
                .filter(p -> !prevCodes.contains(str(p.get("packageCode"))))
                .filter(p -> "LOGISTICS".equals(p.get("sourceSystem")) || "INSTITUTION".equals(p.get("sourceSystem")))
                .map(p -> str(p.get("packageCode"))).toList();
        if (!newLogistics.isEmpty()) {
            types.add("NEW_LOGISTICS_EVIDENCE");
            diffDetail.add(Map.of("type", "NEW_LOGISTICS_EVIDENCE",
                    "description", "新快照中出现旧快照没有的物流/机构凭证包，迟到回报只进入新版本",
                    "packages", newLogistics));
        }

        boolean parseRevised = curPkgs.stream().anyMatch(p -> "PARSE_DIFF".equals(p.get("parseStatus")));
        if (parseRevised) {
            types.add("PARSE_RULE_REVISED");
            diffDetail.add(Map.of("type", "PARSE_RULE_REVISED",
                    "description", "包哈希有效但解析版本落后且字段语义受影响，相关关系边需重新审阅，不回填旧范围",
                    "packages", curPkgs.stream().filter(p -> "PARSE_DIFF".equals(p.get("parseStatus")))
                            .map(p -> str(p.get("packageCode"))).toList()));
        }
        return types.isEmpty() ? "NONE" : types.size() == 1 ? types.get(0) : "MIXED:" + String.join("+", types);
    }

    private List<Map<String, Object>> buildAffectedConclusions(long runId, long prevReleaseId) {
        Long prevRunId = jdbc.queryForObject("SELECT run_id FROM release_version WHERE id=?", Long.class, prevReleaseId);
        Map<String, Map<String, Object>> prevStates = loadStates(prevRunId);
        Map<String, Map<String, Object>> curStates = loadStates(runId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String code : curStates.keySet()) {
            Map<String, Object> cur = curStates.get(code);
            Map<String, Object> prev = prevStates.get(code);
            if (prev == null) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("batchCode", code);
                change.put("changeType", "NEW_AFFECTED_BATCH");
                change.put("previous", null);
                change.put("current", intervalView(cur));
                out.add(change);
            } else if (!sameInterval(prev, cur)) {
                out.add(Map.of("batchCode", code, "changeType", "QUANTITY_INTERVAL_CHANGED",
                        "previous", intervalView(prev), "current", intervalView(cur)));
            }
        }
        for (String code : prevStates.keySet()) {
            if (!curStates.containsKey(code)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("batchCode", code);
                change.put("changeType", "NO_LONGER_AFFECTED");
                change.put("previous", intervalView(prevStates.get(code)));
                change.put("current", null);
                out.add(change);
            }
        }
        return out;
    }

    private void insertReleaseItems(long releaseId, long runId) {
        List<Map<String, Object>> states = jdbc.query(
                "SELECT * FROM quantity_state WHERE run_id=? ORDER BY batch_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("batchCode", rs.getString("batch_code"));
                    m.put("confirmedLower", rs.getBigDecimal("confirmed_lower"));
                    m.put("pendingQty", rs.getBigDecimal("pending_qty"));
                    m.put("possibleUpper", rs.getBigDecimal("possible_upper"));
                    m.put("unit", rs.getString("unit"));
                    m.put("detail", Json.map(rs.getObject("detail")));
                    return m;
                }, runId);
        for (Map<String, Object> s : states) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                    ((Map<String, Object>) s.get("detail")).getOrDefault("nodes", List.of());
            String nodeName = nodes.isEmpty() ? null : str(nodes.get(0).get("node"));
            boolean conflict = nodes.stream().anyMatch(n -> Boolean.TRUE.equals(n.get("quantityConflict")));
            double confirmed = ((Number) s.get("confirmedLower")).doubleValue();
            double pending = ((Number) s.get("pendingQty")).doubleValue();
            double upper = ((Number) s.get("possibleUpper")).doubleValue();
            String grade = confirmed <= 0 && pending > 0 ? "POSSIBLE" : pending > 0 ? "PENDING" : "CONFIRMED";
            jdbc.update("INSERT INTO release_item(release_id, batch_code, node_name, confirmed_qty, pending_qty, "
                            + "possible_qty, lower_bound, upper_bound, unit, evidence_grade, trace_link, conflict_flag) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    releaseId, s.get("batchCode"), nodeName, confirmed, pending, upper, confirmed, upper,
                    s.get("unit"), grade, "/api/runs/" + runId + "/batches/" + s.get("batchCode") + "/trace", conflict);
        }
    }

    public List<Map<String, Object>> listReleases() {
        return jdbc.query("SELECT * FROM release_version ORDER BY version_no DESC", (rs, i) -> {
            long releaseId = rs.getLong("id");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", releaseId);
            m.put("releaseCode", rs.getString("release_code"));
            m.put("versionNo", rs.getInt("version_no"));
            m.put("status", rs.getString("status"));
            m.put("predecessorReleaseCode", rs.getString("predecessor_release_code"));
            m.put("diffType", rs.getString("diff_type"));
            m.put("diffDetail", Json.list(rs.getObject("diff_detail")));
            m.put("basis", Json.map(rs.getObject("basis")));
            m.put("affectedConclusions", Json.list(rs.getObject("affected_conclusions")));
            m.put("publishedAt", rs.getObject("published_at").toString());
            m.put("items", listItems(releaseId, false));
            return m;
        });
    }

    /** 外部脱敏视图：仅白名单字段。 */
    public List<Map<String, Object>> externalReleases() {
        return jdbc.query("SELECT id, release_code, version_no, status, diff_type, published_at "
                        + "FROM release_version WHERE status='PUBLISHED' ORDER BY version_no DESC",
                (rs, i) -> Map.of(
                        "releaseCode", rs.getString("release_code"),
                        "versionNo", rs.getInt("version_no"),
                        "status", rs.getString("status"),
                        "diffType", rs.getString("diff_type"),
                        "publishedAt", rs.getObject("published_at").toString()));
    }

    public Map<String, Object> externalSummary(String releaseCode) {
        Long releaseId = jdbc.query("SELECT id FROM release_version WHERE release_code=? AND status='PUBLISHED'",
                rs -> rs.next() ? rs.getLong("id") : null, releaseCode);
        if (releaseId == null) {
            throw new IllegalArgumentException("已发布版本不存在: " + releaseCode);
        }
        List<Map<String, Object>> safeItems = listItems(releaseId, true);
        return Map.of("releaseCode", releaseCode, "items", safeItems);
    }

    private List<Map<String, Object>> listItems(long releaseId, boolean externalOnly) {
        return jdbc.query("SELECT * FROM release_item WHERE release_id=? ORDER BY id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchCode", rs.getString("batch_code"));
            m.put("nodeName", rs.getString("node_name"));
            m.put("confirmedQty", rs.getBigDecimal("confirmed_qty"));
            m.put("pendingQty", rs.getBigDecimal("pending_qty"));
            m.put("possibleQty", rs.getBigDecimal("possible_qty"));
            m.put("lowerBound", rs.getBigDecimal("lower_bound"));
            m.put("upperBound", rs.getBigDecimal("upper_bound"));
            m.put("unit", rs.getString("unit"));
            m.put("evidenceGrade", rs.getString("evidence_grade"));
            m.put("traceLink", rs.getString("trace_link"));
            m.put("conflictFlag", rs.getBoolean("conflict_flag"));
            if (externalOnly) {
                m.keySet().retainAll(EXTERNAL_ITEM_FIELDS);
            }
            return m;
        }, releaseId);
    }

    private Map<String, Map<String, Object>> loadStates(long runId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        jdbc.query("SELECT batch_code, confirmed_lower, pending_qty, possible_upper, unattributed_delta "
                + "FROM quantity_state WHERE run_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String code = rs.getString("batch_code");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("confirmedLower", rs.getBigDecimal("confirmed_lower"));
                    m.put("pendingQty", rs.getBigDecimal("pending_qty"));
                    m.put("possibleUpper", rs.getBigDecimal("possible_upper"));
                    m.put("unattributedDelta", rs.getBigDecimal("unattributed_delta"));
                    out.put(code, m);
                }, runId);
        return out;
    }

    private Map<String, Object> intervalView(Map<String, Object> s) {
        return Map.of("confirmedLower", s.get("confirmedLower"),
                "pendingQty", s.get("pendingQty"),
                "possibleUpper", s.get("possibleUpper"),
                "unattributedDelta", s.get("unattributedDelta"));
    }

    private boolean sameInterval(Map<String, Object> a, Map<String, Object> b) {
        return a.get("confirmedLower").equals(b.get("confirmedLower"))
                && a.get("pendingQty").equals(b.get("pendingQty"))
                && a.get("possibleUpper").equals(b.get("possibleUpper"));
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
