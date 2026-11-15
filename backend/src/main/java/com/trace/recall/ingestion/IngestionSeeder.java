package com.trace.recall.ingestion;

import com.trace.recall.common.HashUtil;
import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 演示数据播种机：8 个来源包、13 个批次主数据、2 个解析规则版本与外部凭证。
 * 时间线见 README 第 4 节。幂等：已存在数据时跳过。
 */
@Component
public class IngestionSeeder {

    private final JdbcTemplate jdbc;

    public IngestionSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEmpty() {
        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM source_package", Integer.class);
        return cnt == null || cnt == 0;
    }

    public void seed() {
        if (!isEmpty()) {
            return;
        }
        seedParseRules();
        seedApiClient();
        seedBatches();
        seedPackagesAndFacts();
    }

    private void seedParseRules() {
        jdbc.update("INSERT INTO parse_rule(version, effective_at, description, affected_types) VALUES (?,?,?,?)",
                "PARSE-1.9", OffsetDateTime.parse("2026-01-01T00:00:00+08:00"),
                "初版解析规则", "INSPECTION,FEEDING,RELEASE,LOGISTICS,INSTITUTION_REPORT");
        jdbc.update("INSERT INTO parse_rule(version, effective_at, description, affected_types) VALUES (?,?,?,?)",
                "PARSE-2.0", OffsetDateTime.parse("2026-11-05T00:00:00+08:00"),
                "物流与混批字段语义升级：承运节点层级、混批投料占比口径变化", "LOGISTICS,BLEND");
    }

    private void seedApiClient() {
        jdbc.update("INSERT INTO api_client(api_key, name, enabled) VALUES (?,?,true)",
                "ext-trace-key-2026", "外部处置系统");
    }

    private void seedBatches() {
        List<Object[]> rows = List.of(
                new Object[]{"RM-A01", "RAW", "P-AMOX-API", "阿莫西林原料药", "kg", 100.000},
                new Object[]{"RM-A02", "RAW", "P-CEF-API", "头孢菌素原料药", "kg", 80.000},
                new Object[]{"RM-A03", "RAW", "P-MET-API", "二甲双胍原料药", "kg", 60.000},
                new Object[]{"RM-A04", "RAW", "P-DEX-API", "地塞米松原料药", "kg", 60.000},
                new Object[]{"RM-A05", "RAW", "P-LID-API", "利多卡因原料药", "kg", 50.000},
                new Object[]{"IM-B10", "INTERMEDIATE", "I-MIX-10", "混粉中间体 B10", "kg", 45.000},
                new Object[]{"IM-B11", "INTERMEDIATE", "I-MIX-11", "混粉中间体 B11", "kg", 40.000},
                new Object[]{"IM-B30", "INTERMEDIATE", "I-MIX-30", "制粒中间体 B30", "kg", 75.000},
                new Object[]{"FG-C01", "FINISHED", "F-TAB-01", "成品片剂 C01", "kg", 30.000},
                new Object[]{"FG-C02", "FINISHED", "F-TAB-02", "成品片剂 C02", "kg", 28.000},
                new Object[]{"FG-C03", "FINISHED", "F-INJ-03", "成品注射剂 C03", "kg", 30.000},
                new Object[]{"FG-C04", "FINISHED", "F-TAB-04", "成品片剂 C04", "kg", 12.000},
                new Object[]{"FG-C05", "FINISHED", "F-CAP-05", "成品胶囊 C05", "kg", 20.000}
        );
        for (Object[] r : rows) {
            jdbc.update("INSERT INTO batch_master(batch_code, batch_type, product_code, product_name, unit, quantity, attributes) "
                    + "VALUES (?,?,?,?,?,?,?::jsonb)", r[0], r[1], r[2], r[3], r[4], r[5], Json.jsonb(Map.of()).getValue());
        }
    }

    private long insertPackage(String code, String system, String revision, String parseVersion, String parseStatus,
                               OffsetDateTime received, OffsetDateTime event, String supersedes, Object payload) {
        jdbc.update("INSERT INTO source_package(package_code, source_system, source_revision, payload_hash, parse_rule_version, "
                        + "parse_status, received_at, event_time, supersedes_package_code, payload) VALUES (?,?,?,?,?,?,?,?,?,?)",
                code, system, revision, HashUtil.sha256(payload), parseVersion, parseStatus,
                received, event, supersedes, Json.jsonb(payload));
        Long id = jdbc.queryForObject("SELECT id FROM source_package WHERE package_code=?", Long.class, code);
        return id == null ? -1L : id;
    }

    private void insertFact(long packageId, String factType, String factCode, OffsetDateTime eventTime, Object payload) {
        jdbc.update("INSERT INTO raw_fact(package_id, fact_type, fact_code, event_time, payload) VALUES (?,?,?,?,?)",
                packageId, factType, factCode, eventTime, Json.jsonb(payload));
    }

    private void seedPackagesAndFacts() {
        // 1) 主数据 + 身份观测（11-01）
        Object mdmPayload = Map.of(
                "batches", List.of(
                        Map.of("batchCode", "RM-A01", "productCode", "P-AMOX-API"),
                        Map.of("batchCode", "RM-A02", "productCode", "P-CEF-API"),
                        Map.of("batchCode", "RM-A03", "productCode", "P-MET-API"),
                        Map.of("batchCode", "RM-A04", "productCode", "P-DEX-API"),
                        Map.of("batchCode", "RM-A05", "productCode", "P-LID-API")),
                "supplierLots", List.of(
                        Map.of("lot", "SUP-LOT-21", "internalBatch", "RM-A01", "packingVoucher", true),
                        Map.of("lot", "SUP-LOT-22", "internalBatch", "RM-A02", "packingVoucher", false),
                        Map.of("lot", "SUP-LOT-23", "internalBatch", "RM-A02", "packingVoucher", false),
                        Map.of("lot", "SUP-LOT-24", "internalBatch", "RM-A03", "packingVoucher", true),
                        Map.of("lot", "SUP-LOT-25", "internalBatch", "RM-A04", "packingVoucher", true),
                        Map.of("lot", "SUP-LOT-26", "internalBatch", "RM-A05", "packingVoucher", true)),
                "productionLots", List.of(
                        Map.of("lot", "PL-1001", "internalBatch", "FG-C01"),
                        Map.of("lot", "PL-1002", "internalBatch", "FG-C02"),
                        Map.of("lot", "PL-1003", "internalBatch", "FG-C03"),
                        unknownProductionLot()));
        long mdmPkg = insertPackage("PKG-MDM-1", "MDM", "MDM-1.0", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-01T09:00:00+08:00"), OffsetDateTime.parse("2026-11-01T08:30:00+08:00"),
                null, mdmPayload);
        insertFact(mdmPkg, "MASTER_DATA", "F-MASTER-1", OffsetDateTime.parse("2026-11-01T08:30:00+08:00"), mdmPayload);

        // 2) 检验 V1（11-02）
        Object qc1 = Map.of("revision", "QC-REV-1", "conclusion", "初判：RM-A03、RM-A04 不合格",
                "unqualifiedBatches", List.of("RM-A03", "RM-A04"),
                "qualifiedBatches", List.of("RM-A01", "RM-A02", "RM-A05"));
        long qc1Pkg = insertPackage("PKG-QC-1", "QC_LAB", "QC-REV-1", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-02T15:00:00+08:00"), OffsetDateTime.parse("2026-11-02T14:00:00+08:00"),
                null, qc1);
        insertFact(qc1Pkg, "INSPECTION", "F-QC-1", OffsetDateTime.parse("2026-11-02T14:00:00+08:00"), qc1);

        // 3) MES 投料/拆分/混批/放行（11-03）
        Object mesPayload = Map.of("feeding", List.of(
                        Map.of("factCode", "F-FEED-2", "upstream", "RM-A02", "downstream", "IM-B11", "qty", 18.0, "voucherPresent", true),
                        Map.of("factCode", "F-FEED-3", "upstream", "RM-A02", "downstream", "IM-B30", "qty", 12.0, "voucherPresent", false),
                        Map.of("factCode", "F-FEED-4", "upstream", "RM-A03", "downstream", "IM-B10", "qty", 25.0, "voucherPresent", true)),
                "blend", List.of(
                        Map.of("factCode", "F-BLEND-1", "upstream", "RM-A03", "downstream", "IM-B11", "qty", 22.0, "voucherPresent", true)),
                "split", List.of(
                        Map.of("factCode", "F-SPLIT-1", "upstream", "RM-A04", "downstream", "IM-B30", "qty", 40.0),
                        Map.of("factCode", "F-SPLIT-2", "upstream", "RM-A04", "downstream", "IM-B30", "qty", 35.0)),
                "release", List.of(
                        Map.of("factCode", "F-REL-1", "upstream", "IM-B10", "downstream", "FG-C01", "qty", 30.0),
                        Map.of("factCode", "F-REL-2", "upstream", "IM-B11", "downstream", "FG-C02", "qty", 28.0),
                        Map.of("factCode", "F-REL-3", "upstream", "IM-B30", "downstream", "FG-C03", "qty", 30.0),
                        Map.of("factCode", "F-REL-4", "upstream", "IM-B10", "downstream", "FG-C04", "qty", 12.0)));
        long mesPkg = insertPackage("PKG-MES-1", "MES", "MES-3.2", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-03T17:30:00+08:00"), OffsetDateTime.parse("2026-11-03T16:00:00+08:00"),
                null, mesPayload);
        OffsetDateTime mesTime = OffsetDateTime.parse("2026-11-03T16:00:00+08:00");
        for (Object o : (List<?>) ((Map<?, ?>) mesPayload).get("feeding")) {
            insertFact(mesPkg, "FEEDING", "F-" + factCode(o), mesTime, o);
        }
        for (Object o : (List<?>) ((Map<?, ?>) mesPayload).get("blend")) {
            insertFact(mesPkg, "BLEND", "F-" + factCode(o), mesTime, o);
        }
        for (Object o : (List<?>) ((Map<?, ?>) mesPayload).get("split")) {
            insertFact(mesPkg, "SPLIT", "F-" + factCode(o), mesTime, o);
        }
        for (Object o : (List<?>) ((Map<?, ?>) mesPayload).get("release")) {
            insertFact(mesPkg, "RELEASE", "F-" + factCode(o), mesTime, o);
        }

        // 4) 物流包 1（11-06，PARSE-2.0）
        Object log1 = Map.of("shipments", List.of(
                Map.of("factCode", "F-SHIP-1", "batch", "FG-C03", "node", "HOSP-乙", "boxCode", "BOX-9", "signedQty", 50.0),
                Map.of("factCode", "F-SHIP-2", "batch", "FG-C01", "node", "HOSP-甲", "boxCode", "BOX-7", "signedQty", 30.0)));
        long log1Pkg = insertPackage("PKG-LOG-1", "LOGISTICS", "LOG-1.0", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-06T10:00:00+08:00"), OffsetDateTime.parse("2026-11-06T09:00:00+08:00"),
                null, log1);
        for (Object o : (List<?>) ((Map<?, ?>) log1).get("shipments")) {
            insertFact(log1Pkg, "LOGISTICS", "F-" + factCode(o), OffsetDateTime.parse("2026-11-06T09:00:00+08:00"), o);
        }

        // 5) 物流包 2（11-06，仍按 PARSE-1.9 解析 → PARSE_DIFF；重复箱码 BOX-9；无法匹配的生产批号）
        Object log2 = Map.of("shipments", List.of(
                        Map.of("factCode", "F-SHIP-3", "batch", "FG-C03", "node", "HOSP-丙", "boxCode", "BOX-9", "signedQty", 20.0)),
                "productionLots", List.of(unknownProductionLot()));
        long log2Pkg = insertPackage("PKG-LOG-2", "LOGISTICS", "LOG-1.1", "PARSE-1.9", "PARSE_DIFF",
                OffsetDateTime.parse("2026-11-06T11:30:00+08:00"), OffsetDateTime.parse("2026-11-06T10:30:00+08:00"),
                null, log2);
        for (Object o : (List<?>) ((Map<?, ?>) log2).get("shipments")) {
            insertFact(log2Pkg, "LOGISTICS", "F-" + factCode(o),
                    OffsetDateTime.parse("2026-11-06T10:30:00+08:00"), o);
        }
        insertFact(log2Pkg, "LOGISTICS", "F-LOG-UNKNOWN-9", OffsetDateTime.parse("2026-11-06T10:31:00+08:00"),
                unknownProductionLot());

        // 6) 机构回报 R1/R2（11-07）
        Object inst1 = Map.of("reports", List.of(
                Map.of("reportCode", "R1", "batch", "FG-C03", "node", "HOSP-乙", "receivedQty", 46.0),
                Map.of("reportCode", "R2", "batch", "FG-C01", "node", "HOSP-甲", "receivedQty", 30.0)));
        long inst1Pkg = insertPackage("PKG-INST-1", "INSTITUTION", "INST-1.0", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-07T14:00:00+08:00"), OffsetDateTime.parse("2026-11-07T13:30:00+08:00"),
                null, inst1);
        for (Object o : (List<?>) ((Map<?, ?>) inst1).get("reports")) {
            insertFact(inst1Pkg, "INSTITUTION_REPORT", "F-" + reportCode(o),
                    OffsetDateTime.parse("2026-11-07T13:30:00+08:00"), o);
        }

        // 7) 检验更正 V2（11-12，替代 PKG-QC-1）
        Object qc2 = Map.of("revision", "QC-REV-2", "conclusion", "更正：RM-A01 改判不合格、RM-A03 复检合格",
                "unqualifiedBatches", List.of("RM-A01", "RM-A04"),
                "requalifiedBatches", List.of("RM-A03"));
        long qc2Pkg = insertPackage("PKG-QC-2", "QC_LAB", "QC-REV-2", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-12T16:00:00+08:00"), OffsetDateTime.parse("2026-11-12T15:00:00+08:00"),
                "PKG-QC-1", qc2);
        insertFact(qc2Pkg, "INSPECTION", "F-QC-2", OffsetDateTime.parse("2026-11-12T15:00:00+08:00"), qc2);

        // 8) 迟到机构回报 R3（11-13）
        Object inst2 = Map.of("reports", List.of(
                Map.of("reportCode", "R3", "batch", "FG-C03", "node", "HOSP-丙", "receivedQty", 20.0)));
        long inst2Pkg = insertPackage("PKG-INST-2", "INSTITUTION", "INST-1.1", "PARSE-2.0", "PARSED",
                OffsetDateTime.parse("2026-11-13T11:00:00+08:00"), OffsetDateTime.parse("2026-11-13T10:00:00+08:00"),
                null, inst2);
        for (Object o : (List<?>) ((Map<?, ?>) inst2).get("reports")) {
            insertFact(inst2Pkg, "INSTITUTION_REPORT", "F-" + reportCode(o),
                    OffsetDateTime.parse("2026-11-13T10:00:00+08:00"), o);
        }
    }

    @SuppressWarnings("unchecked")
    private String factCode(Object o) {
        return ((Map<String, Object>) o).get("factCode").toString();
    }

    private Map<String, Object> unknownProductionLot() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("lot", "PL-UNKNOWN-9");
        m.put("internalBatch", null);
        return m;
    }

    @SuppressWarnings("unchecked")
    private String reportCode(Object o) {
        return ((Map<String, Object>) o).get("reportCode").toString();
    }
}
