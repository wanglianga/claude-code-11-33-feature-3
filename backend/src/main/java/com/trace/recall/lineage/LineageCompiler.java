package com.trace.recall.lineage;

import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 谱系编译器：把快照内事实编译为不可变有向关系边。
 * - 重复箱码：同一箱码出现在两条互不相连运输记录 → 两条运输边均降 SUSPECTED（DUPLICATE_BOX）
 * - 解析差异：PARSE_DIFF 包中受影响事实类型的边降级，不影响其他来源/类型的边
 * - 缺失投料凭证：边置 review_required 并登记缺口，但不被解析差异规则连坐
 */
@Service
public class LineageCompiler {

    private final JdbcTemplate jdbc;

    public LineageCompiler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Pkg(long id, String code, String parseStatus, String parseVersion, String affectedTypes) {
    }

    public void compile(long runId, List<Pkg> packages) {
        List<Map<String, Object>> shipmentRows = new ArrayList<>();

        for (Pkg pkg : packages) {
            List<Map<String, Object>> facts = jdbc.query(
                    "SELECT fact_type, fact_code, event_time, payload FROM raw_fact WHERE package_id=? ORDER BY id",
                    (rs, i) -> Map.of(
                            "factType", rs.getString("fact_type"),
                            "factCode", rs.getString("fact_code"),
                            "eventTime", rs.getObject("event_time").toString(),
                            "payload", Json.map(rs.getObject("payload"))),
                    pkg.id());

            for (Map<String, Object> fact : facts) {
                String type = (String) fact.get("factType");
                String factCode = (String) fact.get("factCode");
                String eventTime = (String) fact.get("eventTime");
                Map<String, Object> payload = castMap(fact.get("payload"));
                boolean parseDiffHit = "PARSE_DIFF".equals(pkg.parseStatus())
                        && affected(pkg.affectedTypes(), type);
                switch (type) {
                    case "FEEDING", "SPLIT", "BLEND", "RELEASE" ->
                            compileMesEdge(runId, pkg, type, factCode, eventTime, payload, parseDiffHit);
                    case "LOGISTICS" -> {
                        List<Map<String, Object>> rows = collectShipments(payload);
                        for (Map<String, Object> row : rows) {
                            row.put("_pkg", pkg);
                            row.put("_parseDiffHit", parseDiffHit);
                            row.put("_eventTime", eventTime);
                            shipmentRows.add(row);
                        }
                        registerUnknownLots(runId, pkg, payload);
                    }
                    default -> {
                        // INSPECTION / INSTITUTION_REPORT / MASTER_DATA 不产生谱系边
                    }
                }
            }
        }
        compileShipments(runId, shipmentRows);
    }

    private void compileMesEdge(long runId, Pkg pkg, String type, String factCode, String eventTime,
                                Map<String, Object> payload, boolean parseDiffHit) {
        // BLEND 事实在播种时以列表形式存放在 payload.blend；其余为单条对象或列表
        List<Map<String, Object>> records = new ArrayList<>();
        if (payload.containsKey("factCode")) {
            records.add(payload);
        } else {
            Object list = payload.get(switch (type) {
                case "FEEDING" -> "feeding";
                case "SPLIT" -> "split";
                case "BLEND" -> "blend";
                default -> "release";
            });
            if (list instanceof List<?> l) {
                l.forEach(o -> records.add(castMap(o)));
            }
        }
        for (Map<String, Object> r : records) {
            String code = str(r.getOrDefault("factCode", factCode));
            String upstream = str(r.get("upstream"));
            String downstream = str(r.get("downstream"));
            Double qty = num(r.get("qty"));
            boolean voucherMissing = Boolean.FALSE.equals(r.get("voucherPresent"));
            String confidence = "CONFIRMED";
            boolean reviewRequired = false;
            String blockReason = null;
            if (voucherMissing) {
                reviewRequired = true;
                blockReason = "MISSING_FEEDING_EVIDENCE";
                insertGap(runId, "MISSING_FEEDING_EVIDENCE", "BLOCKER", upstream + "->" + downstream, null,
                        "投料事实 " + code + " 缺少可验证投料凭证，不能单独证明投料关系",
                        upstream + " 到 " + downstream + " 的投料量确认",
                        List.of(Map.of("fact", code, "package", pkg.code())));
            }
            if (parseDiffHit) {
                confidence = "SUSPECTED";
                reviewRequired = true;
                insertGap(runId, "PARSE_DIFF", "WARNING", code, null,
                        "来源包 " + pkg.code() + " 按 " + pkg.parseVersion() + " 解析，字段语义已受规则升级影响",
                        type + " 边 " + code + " 需按新规则重新审阅，不回填旧范围",
                        List.of(Map.of("fact", code, "package", pkg.code(), "parseVersion", pkg.parseVersion())));
            }
            insertEdge(runId, code, upstream, downstream, type, code, pkg.code(), eventTime,
                    confidence, reviewRequired, blockReason, pkg.parseVersion(), qty, "kg",
                    Map.of("voucherPresent", !voucherMissing));
        }
    }

    private void compileShipments(long runId, List<Map<String, Object>> rows) {
        // 重复箱码：同一 boxCode 出现在多条运输记录
        Map<String, List<Map<String, Object>>> byBox = new HashMap<>();
        for (Map<String, Object> r : rows) {
            byBox.computeIfAbsent(str(r.get("boxCode")), k -> new ArrayList<>()).add(r);
        }
        for (Map<String, Object> r : rows) {
            Pkg pkg = (Pkg) r.get("_pkg");
            String box = str(r.get("boxCode"));
            boolean duplicate = byBox.getOrDefault(box, List.of()).size() >= 2;
            boolean parseDiffHit = Boolean.TRUE.equals(r.get("_parseDiffHit"));
            String confidence = "CONFIRMED";
            boolean review = false;
            String blockReason = null;
            if (duplicate) {
                confidence = "SUSPECTED";
                review = true;
                blockReason = "DUPLICATE_BOX";
                insertGap(runId, "DUPLICATE_BOX", "BLOCKER", box, str(r.get("node")),
                        "箱码 " + box + " 在两条互不相连的运输记录中重复，重复载体证据不能证明任一链路完整",
                        "经由箱码 " + box + " 的运输链路完整性",
                        List.of(Map.of("boxCode", box, "fact", r.get("factCode"),
                                "node", r.get("node"), "package", pkg.code())));
            }
            if (parseDiffHit) {
                confidence = "SUSPECTED";
                review = true;
                insertGap(runId, "PARSE_DIFF", "WARNING", str(r.get("factCode")), null,
                        "来源包 " + pkg.code() + " 按 " + pkg.parseVersion() + " 解析，LOGISTICS 字段语义已变化",
                        "运输边 " + r.get("factCode") + " 需重新审阅，不回填旧范围",
                        List.of(Map.of("fact", r.get("factCode"), "package", pkg.code()),
                                Map.of("parseVersion", pkg.parseVersion())));
            }
            insertEdge(runId, "E-" + str(r.get("factCode")), null, str(r.get("batch")), "SHIPMENT",
                    str(r.get("factCode")), pkg.code(), str(r.get("_eventTime")),
                    confidence, review, blockReason, pkg.parseVersion(),
                    num(r.get("signedQty")), "kg",
                    Map.of("boxCode", box, "node", r.get("node")));
        }
    }

    private List<Map<String, Object>> collectShipments(Map<String, Object> payload) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object shipments = payload.get("shipments");
        if (shipments instanceof List<?> l) {
            l.forEach(o -> out.add(castMap(o)));
        } else if (payload.containsKey("factCode") && payload.containsKey("boxCode")) {
            out.add(payload);  // 单条运输事实
        }
        return out;
    }

    /** 未匹配生产批号：兼容包级 productionLots 列表与单条 lot 事实两种载荷形态。 */
    private void registerUnknownLots(long runId, Pkg pkg, Map<String, Object> payload) {
        Object lots = payload.get("productionLots");
        if (lots instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> lot = castMap(o);
                if (lot.get("internalBatch") == null) {
                    insertGap(runId, "UNMATCHED_BATCH", "WARNING", str(lot.get("lot")), null,
                            "生产批号 " + lot.get("lot") + " 无法匹配任何内部批号，相关运输事实不能归因",
                            "该批号无法进入谱系链路",
                            List.of(Map.of("package", pkg.code(), "productionLot", lot.get("lot"))));
                }
            }
        } else if (payload.containsKey("lot") && payload.get("internalBatch") == null) {
            insertGap(runId, "UNMATCHED_BATCH", "WARNING", str(payload.get("lot")), null,
                    "生产批号 " + payload.get("lot") + " 无法匹配任何内部批号，相关运输事实不能归因",
                    "该批号无法进入谱系链路",
                    List.of(Map.of("package", pkg.code(), "productionLot", payload.get("lot"))));
        }
    }

    private void insertEdge(long runId, String edgeCode, String upstream, String downstream, String relationType,
                            String factCode, String packageCode, String validFrom, String confidence,
                            boolean reviewRequired, String blockReason, String parseVersion,
                            Double qty, String unit, Map<String, Object> detail) {
        jdbc.update("INSERT INTO lineage_edge(run_id, edge_code, upstream_batch, downstream_batch, relation_type, "
                        + "source_fact_code, source_package_code, valid_from, confidence_status, review_required, "
                        + "block_reason, parse_rule_version, quantity, unit, edge_detail) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, edgeCode, upstream, downstream, relationType, factCode, packageCode,
                com.trace.recall.common.TimeUtil.parseFlexible(validFrom), confidence, reviewRequired,
                blockReason, parseVersion, qty, unit, Json.jsonb(detail));
    }

    private void insertGap(long runId, String type, String severity, String subject, String node,
                           String description, String blocks, Object evidence) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_gap WHERE run_id=? AND gap_type=? AND subject_code=?",
                Integer.class, runId, type, subject);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("INSERT INTO evidence_gap(run_id, gap_type, severity, subject_code, node_name, description, "
                + "blocks_conclusion, evidence) VALUES (?,?,?,?,?,?,?,?)",
                runId, type, severity, subject, node, description, blocks, Json.jsonb(evidence));
    }

    private boolean affected(String affectedTypes, String factType) {
        if (affectedTypes == null) {
            return false;
        }
        for (String t : affectedTypes.split(",")) {
            if (t.trim().equals(factType)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private Double num(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }
}
