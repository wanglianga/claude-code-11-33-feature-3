package com.trace.recall.quality;

import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数量质量引擎：
 * - 超额投料：拆分/投料合计超过原料可用量 → OVER_FEEDING，边 FROZEN 并沿下游 BFS 传播，确认量冻结
 * - 数量冲突：物流签收与机构回报双凭证并存且数值不一致 → 两个数字都保留，任一来源不得覆盖另一来源
 * - 身份歧义：保留双候选，可能上界按候选口径放宽
 * 不变式：确认下界 + 待核 = 可能上界；超额部分另列未归因差额。
 */
@Service
public class QuantityQualityEngine {

    private final JdbcTemplate jdbc;

    public QuantityQualityEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void evaluate(long runId, List<Long> snapshotPackageIds, List<String> unqualifiedBatches) {
        List<Map<String, Object>> edges = loadEdges(runId);
        Map<String, Double> masterQty = loadMasterQuantities();

        // 1) 超额投料检测 + 冻结传播（直接更新数据库，随后重新加载以拿到 FROZEN 状态）
        List<String> frozenEdgeCodes = new ArrayList<>();
        Map<String, Double> overDelta = new HashMap<>();
        for (String batch : unqualifiedBatches) {
            double outgoing = edges.stream()
                    .filter(e -> batch.equals(e.get("upstream")) && !"SHIPMENT".equals(e.get("relationType")))
                    .mapToDouble(e -> ((Number) e.get("quantity")).doubleValue()).sum();
            Double available = masterQty.get(batch);
            if (available != null && outgoing > available + 0.0001) {
                double delta = round(outgoing - available);
                overDelta.put(batch, delta);
                insertGap(runId, "OVER_FEEDING", "BLOCKER", batch, null,
                        "批次 " + batch + " 拆分/投料合计 " + round(outgoing) + "kg 超过可用量 " + available
                                + "kg，超额 " + delta + "kg 无法归因，整条分配链确认量冻结",
                        batch + " 下游全部代际的确认量",
                        List.of(Map.of("batch", batch, "available", available,
                                "planned", round(outgoing), "delta", delta)));
                frozenEdgeCodes.addAll(propagateFreeze(runId, batch, edges));
            }
        }
        // 冻结已落库，重新加载边，避免用旧的 CONFIRMED 状态计算下游确认量
        edges = loadEdges(runId);

        // 2) 身份歧义缺口
        List<Map<String, Object>> ambiguous = jdbc.query(
                "SELECT code_value, observed_batch_code FROM identity_record WHERE run_id=? AND match_status='AMBIGUOUS'",
                (rs, i) -> Map.of("codeValue", rs.getString("code_value"),
                        "observedBatch", rs.getString("observed_batch_code")), runId);
        for (Map<String, Object> a : ambiguous) {
            insertGap(runId, "IDENTITY_AMBIGUITY", "WARNING", str(a.get("codeValue")), null,
                    "供应商批号 " + a.get("codeValue") + " 与另一供应商批号同时指向 "
                            + a.get("observedBatch") + " 且均无装箱凭证，保留双候选不合并，可能上界放宽",
                    a.get("observedBatch") + " 的唯一归因",
                    List.of(a));
        }

        // 3) 受影响集合：不合格原料沿非运输边向下游闭包传播
        Map<String, List<Map<String, Object>>> incoming = new HashMap<>();
        Map<String, List<Map<String, Object>>> adjacency = new HashMap<>();
        for (Map<String, Object> e : edges) {
            if ("SHIPMENT".equals(e.get("relationType")) || e.get("upstream") == null) {
                continue;
            }
            incoming.computeIfAbsent(str(e.get("downstream")), k -> new ArrayList<>()).add(e);
            adjacency.computeIfAbsent(str(e.get("upstream")), k -> new ArrayList<>()).add(e);
        }
        List<String> affected = downstreamClosure(unqualifiedBatches, adjacency);

        // 4) 逐批次数量区间
        for (String batch : affected) {
            double confirmed;
            double pending;
            double delta = overDelta.getOrDefault(batch, 0.0);
            List<Map<String, Object>> ins = incoming.getOrDefault(batch, List.of());
            if (ins.isEmpty()) {
                // 源头批次：以批次主数据可用量为确认下界
                confirmed = masterQty.getOrDefault(batch, 0.0);
                pending = 0.0;
            } else {
                confirmed = 0.0;
                pending = 0.0;
                for (Map<String, Object> e : ins) {
                    double q = ((Number) e.get("quantity")).doubleValue();
                    String status = str(e.get("confidenceStatus"));
                    String block = str(e.get("blockReason"));
                    if ("FROZEN".equals(status)) {
                        pending += q;                       // 冻结链：不能确认，计入待核
                    } else if ("MISSING_FEEDING_EVIDENCE".equals(block)) {
                        // 缺凭证边不进入数量合计，仅以缺口形式存在
                    } else if ("SUSPECTED".equals(status)) {
                        pending += q;
                    } else {
                        confirmed += q;
                    }
                }
            }
            confirmed = round(confirmed);
            pending = round(pending);
            double upper = round(confirmed + pending);     // 不变式：下界 + 待核 = 上界
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("overFeeding", overDelta.containsKey(batch));
            detail.put("unattributedReason", delta > 0 ? "投料合计超过可用量，超额部分无法归因" : null);
            jdbc.update("INSERT INTO quantity_state(run_id, batch_code, affected_flag, confirmed_lower, pending_qty, "
                            + "possible_upper, unattributed_delta, unit, detail) VALUES (?,?,?,?,?,?,?,?,?)",
                    runId, batch, true, confirmed, pending, upper, delta, "kg", Json.jsonb(detail));
        }

        // 5) 机构/物流节点数量挂载与双凭证冲突
        attachNodeQuantities(runId, snapshotPackageIds);
    }

    /** 冻结从超额批次出发的全部下游边，BFS 传播到所有代际，返回被冻结的边编码。 */
    private List<String> propagateFreeze(long runId, String rootBatch, List<Map<String, Object>> edges) {
        List<String> frozen = new ArrayList<>();
        Map<String, List<Map<String, Object>>> adjacency = new HashMap<>();
        for (Map<String, Object> e : edges) {
            if ("SHIPMENT".equals(e.get("relationType")) || e.get("upstream") == null) {
                continue;
            }
            adjacency.computeIfAbsent(str(e.get("upstream")), k -> new ArrayList<>()).add(e);
        }
        List<String> queue = new ArrayList<>(List.of(rootBatch));
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            for (Map<String, Object> e : adjacency.getOrDefault(current, List.of())) {
                String edgeCode = str(e.get("edgeCode"));
                if (frozen.contains(edgeCode)) {
                    continue;
                }
                frozen.add(edgeCode);
                jdbc.update("UPDATE lineage_edge SET confidence_status='FROZEN', review_required=true, "
                        + "block_reason=COALESCE(block_reason,'OVER_FEEDING') WHERE run_id=? AND edge_code=?",
                        runId, edgeCode);
                queue.add(str(e.get("downstream")));
            }
        }
        return frozen;
    }

    private List<String> downstreamClosure(List<String> roots, Map<String, List<Map<String, Object>>> adjacency) {
        List<String> out = new ArrayList<>();
        List<String> queue = new ArrayList<>(roots);
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (out.contains(current)) {
                continue;
            }
            out.add(current);
            for (Map<String, Object> e : adjacency.getOrDefault(current, List.of())) {
                String down = str(e.get("downstream"));
                if (!out.contains(down)) {
                    queue.add(down);
                }
            }
        }
        return out;
    }

    /**
     * 节点数量：物流签收量与机构回报量按 批次+节点 对齐。
     * 双凭证数值不一致 → QUANTITY_CONFLICT，两个数字同时保留；仅单凭证 → 证据薄弱。
     */
    private void attachNodeQuantities(long runId, List<Long> snapshotPackageIds) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> shipEdges = jdbc.query(
                "SELECT edge_code, downstream_batch, quantity, edge_detail, source_fact_code, source_package_code "
                        + "FROM lineage_edge WHERE run_id=? AND relation_type='SHIPMENT'",
                (rs, i) -> Map.of(
                        "batch", rs.getString("downstream_batch"),
                        "qty", rs.getBigDecimal("quantity").doubleValue(),
                        "detail", Json.map(rs.getObject("edge_detail")),
                        "factCode", rs.getString("source_fact_code"),
                        "package", rs.getString("source_package_code")), runId);
        for (Map<String, Object> s : shipEdges) {
            Map<String, Object> d = castMap(s.get("detail"));
            String node = str(d.get("node"));
            String key = s.get("batch") + "@" + node;
            Map<String, Object> view = nodes.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("node", node);
                m.put("boxCode", d.get("boxCode"));
                m.put("batch", s.get("batch"));
                return m;
            });
            view.put("logisticsSignedQty", s.get("qty"));
            view.put("logisticsFactCode", s.get("factCode"));
            view.put("logisticsPackage", s.get("package"));
        }

        for (Long pkgId : snapshotPackageIds) {
            List<Map<String, Object>> reports = jdbc.query(
                    "SELECT payload FROM raw_fact WHERE package_id=? AND fact_type='INSTITUTION_REPORT'",
                    (rs, i) -> Json.map(rs.getObject("payload")), pkgId);
            for (Map<String, Object> payload : reports) {
                List<?> rawList = payload.containsKey("reports")
                        ? (List<?>) payload.get("reports") : List.of(payload);
                for (Object o : rawList) {
                    Map<String, Object> r = castMap(o);
                    String key = r.get("batch") + "@" + r.get("node");
                    Map<String, Object> view = nodes.computeIfAbsent(key, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("node", r.get("node"));
                        m.put("batch", r.get("batch"));
                        return m;
                    });
                    view.put("institutionReceivedQty", ((Number) r.get("receivedQty")).doubleValue());
                    view.put("institutionFactCode", "F-" + r.get("reportCode"));
                }
            }
        }

        Map<String, List<Map<String, Object>>> byBatch = new LinkedHashMap<>();
        for (Map<String, Object> v : nodes.values()) {
            String batch = str(v.get("batch"));
            boolean hasLog = v.containsKey("logisticsSignedQty");
            boolean hasInst = v.containsKey("institutionReceivedQty");
            boolean conflict = hasLog && hasInst
                    && Math.abs(((Number) v.get("logisticsSignedQty")).doubleValue()
                    - ((Number) v.get("institutionReceivedQty")).doubleValue()) > 0.0001;
            v.put("quantityConflict", conflict);
            if (conflict) {
                v.put("evidenceWeak", false);
                insertGap(runId, "QUANTITY_CONFLICT", "BLOCKER", batch, str(v.get("node")),
                        "节点 " + v.get("node") + " 物流签收 " + v.get("logisticsSignedQty")
                                + "kg 与机构回报 " + v.get("institutionReceivedQty")
                                + "kg 不一致，双凭证并存，任一来源不得覆盖另一来源",
                        batch + " 在 " + v.get("node") + " 的确认数量",
                        List.of(Map.of("logistics", v.get("logisticsFactCode"),
                                "institution", v.get("institutionFactCode"),
                                "logisticsQty", v.get("logisticsSignedQty"),
                                "institutionQty", v.get("institutionReceivedQty"))));
            } else if (!hasLog || !hasInst) {
                v.put("evidenceWeak", true);
                v.put("weakReason", hasLog ? "仅有物流签收凭证，缺机构回报" : "仅有机构回报，缺物流签收凭证");
            } else {
                v.put("evidenceWeak", false);
            }
            byBatch.computeIfAbsent(batch, k -> new ArrayList<>()).add(v);
        }

        // 节点证据只挂载到已进入受影响闭包（已有 quantity_state）的批次；
        // 仅有物流/机构证据但不在受影响范围的批次不得被标记为受影响。
        for (Map.Entry<String, List<Map<String, Object>>> e : byBatch.entrySet()) {
            jdbc.update("UPDATE quantity_state SET detail=? WHERE run_id=? AND batch_code=?",
                    Json.jsonb(Map.of("nodes", e.getValue(),
                            "overFeeding", isOverFeeding(runId, e.getKey()))), runId, e.getKey());
        }
    }

    private boolean isOverFeeding(long runId, String batch) {
        Long cnt = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_gap WHERE run_id=? AND gap_type='OVER_FEEDING' AND subject_code=?",
                Long.class, runId, batch);
        return cnt != null && cnt > 0;
    }

    private List<Map<String, Object>> loadEdges(long runId) {
        return jdbc.query("SELECT edge_code, upstream_batch, downstream_batch, relation_type, confidence_status, "
                + "block_reason, quantity FROM lineage_edge WHERE run_id=? ORDER BY id", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("edgeCode", rs.getString("edge_code"));
            m.put("upstream", rs.getString("upstream_batch"));
            m.put("downstream", rs.getString("downstream_batch"));
            m.put("relationType", rs.getString("relation_type"));
            m.put("confidenceStatus", rs.getString("confidence_status"));
            m.put("blockReason", rs.getString("block_reason"));
            m.put("quantity", rs.getBigDecimal("quantity"));
            return m;
        }, runId);
    }

    private Map<String, Double> loadMasterQuantities() {
        Map<String, Double> out = new HashMap<>();
        jdbc.query("SELECT batch_code, quantity FROM batch_master",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        out.put(rs.getString("batch_code"), rs.getBigDecimal("quantity").doubleValue()));
        return out;
    }

    private void insertGap(long runId, String type, String severity, String subject, String node,
                           String description, String blocks, Object evidence) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_gap WHERE run_id=? AND gap_type=? AND subject_code=? AND node_name IS NOT DISTINCT FROM ?",
                Integer.class, runId, type, subject, node);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, node_name, description, "
                + "blocks_conclusion, evidence) VALUES (?,?,?,?,?,?,?,?)",
                runId, type, severity, subject, node, description, blocks, Json.jsonb(evidence));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
