package com.trace.recall.substitution;

import com.trace.recall.common.HashUtil;
import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 替代目录域演示/验收数据播种机。
 * 受理层只录入来源事实（文档/条目版本/受控属性回链），声明、矛盾与反例由 {@link SuitabilityEngine} 派生。
 * 覆盖五类验收样例：单位不可换算、同名异剂型、过期药典、限制条件缺失、包装层级变更，
 * 另含一条全证据通过的干净基线，证明“可比较”同样需要属性级依据。
 */
@Component
public class SubstitutionSeeder {

    public static final String SCOPE_CODE = "SCOPE-2026-11";

    private final JdbcTemplate jdbc;
    private final SuitabilityEngine engine;
    private final AnalysisPublicationService publicationService;

    public SubstitutionSeeder(JdbcTemplate jdbc, SuitabilityEngine engine,
                              AnalysisPublicationService publicationService) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.publicationService = publicationService;
    }

    @Transactional
    public void seed() {
        seedScopeAndTargets();
        seedDocuments();
        seedEntriesAndVersions();
        engine.deriveAll();
        // 两个分析请求日期，验证包装层级变更下的历史版本选择
        publicationService.createRequest(SCOPE_CODE, "2026-10-15T10:00:00+08:00", "质量分析员-张某");
        publicationService.createRequest(SCOPE_CODE, "2026-11-15T10:00:00+08:00", "质量分析员-张某");
    }

    private void seedScopeAndTargets() {
        jdbc.update("INSERT INTO batch_scope(scope_code, scope_name, effective_from, effective_to, remark) "
                + "VALUES (?,?,?,?,?)", SCOPE_CODE, "2026 年 11 月已发布批次范围（替代目录适用性分析）",
                OffsetDateTime.parse("2026-11-01T00:00:00+08:00"),
                OffsetDateTime.parse("2026-11-30T23:59:59+08:00"),
                "仅提供待分析产品标识与生效日期，不含库存/需求/调拨信息");
        long scopeId = jdbc.queryForObject("SELECT id FROM batch_scope WHERE scope_code=?", Long.class, SCOPE_CODE);
        List<Object[]> targets = List.of(
                new Object[]{"TGT-01", "阿莫西林胶囊", Map.of("dosageForm", "CAPSULE", "concentration", "250 mg/粒",
                        "route", "ORAL", "sterilityGrade", "NON_STERILE")},
                new Object[]{"TGT-02", "注射用头孢唑林钠", Map.of("dosageForm", "INJECTION", "concentration", "10 mg/mL",
                        "route", "INTRAVENOUS", "sterilityGrade", "STERILE")},
                new Object[]{"TGT-03", "同松口服片", Map.of("dosageForm", "TABLET", "concentration", "5 mg/片",
                        "route", "ORAL", "sterilityGrade", "NON_STERILE")},
                new Object[]{"TGT-04", "盐酸利多卡因注射液", Map.of("dosageForm", "INJECTION", "concentration", "20 mg/mL",
                        "route", "INTRAVENOUS", "sterilityGrade", "STERILE")},
                new Object[]{"TGT-05", "二甲双胍片", Map.of("dosageForm", "TABLET", "concentration", "500 mg/片",
                        "route", "ORAL", "sterilityGrade", "NON_STERILE")},
                new Object[]{"TGT-06", "氯化钠注射液", Map.of("dosageForm", "INJECTION", "concentration", "9 mg/mL",
                        "route", "INTRAVENOUS", "sterilityGrade", "STERILE")});
        for (Object[] t : targets) {
            jdbc.update("INSERT INTO scope_target(scope_id, target_product_code, target_product_name, target_spec) "
                    + "VALUES (?,?,?,?)", scopeId, t[0], t[1], Json.jsonb(t[2]));
        }
    }

    private long doc(String code, String type, String source, String revision, String location,
                     OffsetDateTime received, OffsetDateTime from, OffsetDateTime to, String supersedes, Object payload) {
        jdbc.update("INSERT INTO catalog_document(doc_code, doc_type, source_name, source_revision, source_location, "
                        + "payload_hash, raw_payload, received_at, effective_from, effective_to, supersedes_doc_code) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                code, type, source, revision, location, HashUtil.sha256(payload), Json.jsonb(payload),
                received, from, to, supersedes);
        return jdbc.queryForObject("SELECT id FROM catalog_document WHERE doc_code=?", Long.class, code);
    }

    private void seedDocuments() {
        doc("DOC-SUP-A", "SUPPLIER_CATALOG", "供应商甲目录", "SUP-A-2026.11",
                "supplier-a/catalog-2026-11.xlsx#Sheet1", OffsetDateTime.parse("2026-11-01T09:00:00+08:00"),
                OffsetDateTime.parse("2026-11-01T00:00:00+08:00"), null, null,
                Map.of("catalogName", "供应商甲 2026-11 产品目录", "entries", List.of("E1", "E2", "E3", "E4", "E5", "E6")));
        doc("DOC-SUP-B", "SUPPLIER_CATALOG", "供应商乙目录", "SUP-B-2026.11",
                "supplier-b/catalog-2026-11.pdf#p12", OffsetDateTime.parse("2026-11-02T09:00:00+08:00"),
                OffsetDateTime.parse("2026-11-01T00:00:00+08:00"), null, null,
                Map.of("catalogName", "供应商乙 2026-11 产品目录", "note", "浓度按每支标注，未给包装体积"));
        doc("DOC-PHARM-CUR", "PHARMACOPOEIA_REFERENCE", "国家药典委员会", "CHP-2025",
                "chp/2025/vol1#sec-注射剂", OffsetDateTime.parse("2026-01-05T09:00:00+08:00"),
                OffsetDateTime.parse("2026-01-01T00:00:00+08:00"), null, null,
                Map.of("pharmacopoeia", "中国药典 2025 年版", "status", "CURRENT"));
        doc("DOC-PHARM-OLD", "PHARMACOPOEIA_REFERENCE", "国家药典委员会", "CHP-2020",
                "chp/2020/vol2#sec-利多卡因", OffsetDateTime.parse("2021-01-05T09:00:00+08:00"),
                OffsetDateTime.parse("2021-01-01T00:00:00+08:00"),
                OffsetDateTime.parse("2026-10-31T23:59:59+08:00"), null,
                Map.of("pharmacopoeia", "中国药典 2020 年版", "status", "EXPIRED"));
        doc("DOC-SCN-E4", "SPEC_CHANGE_NOTICE", "供应商甲目录修订通知", "SCN-E4-2026.11",
                "supplier-a/notice-SCN-E4.pdf#p2", OffsetDateTime.parse("2026-11-03T09:00:00+08:00"),
                OffsetDateTime.parse("2026-11-01T00:00:00+08:00"), null, "DOC-PHARM-OLD",
                Map.of("subject", "ENTRY-E4 药典引用到期",
                        "note", "仅告知旧药典到期，未给出替代依据（replacementBasis 字段缺失）"));
        doc("DOC-SCN-E6", "SPEC_CHANGE_NOTICE", "供应商甲目录修订通知", "SCN-E6-2026.11",
                "supplier-a/notice-SCN-E6.pdf#p1", OffsetDateTime.parse("2026-10-28T09:00:00+08:00"),
                OffsetDateTime.parse("2026-11-01T00:00:00+08:00"), null, null,
                Map.of("subject", "ENTRY-E6 包装层级变更", "fromLevel", "盒/10支", "toLevel", "箱/100盒",
                        "effectiveFrom", "2026-11-01"));
        doc("DOC-MDM", "SUPPLIER_CATALOG", "规格主数据 MDM", "MDM-SPEC-2026.11",
                "mdm/spec-master#controlled-attributes", OffsetDateTime.parse("2026-11-01T08:00:00+08:00"),
                OffsetDateTime.parse("2026-11-01T00:00:00+08:00"), null, null,
                Map.of("master", "受控属性主数据", "note", "剂型/途径/无菌等级受控词表来源"));
    }

    private long entry(String code, String name, String manufacturer, String target) {
        jdbc.update("INSERT INTO catalog_entry(entry_code, product_name, manufacturer, target_product_code) "
                + "VALUES (?,?,?,?)", code, name, manufacturer, target);
        return jdbc.queryForObject("SELECT id FROM catalog_entry WHERE entry_code=?", Long.class, code);
    }

    private long version(long entryId, int versionNo, long docId, String location,
                         OffsetDateTime from, OffsetDateTime to, Object rawRecord) {
        jdbc.update("INSERT INTO catalog_entry_version(entry_id, version_no, doc_id, source_location, effective_from, "
                + "effective_to, raw_record) VALUES (?,?,?,?,?,?,?)",
                entryId, versionNo, docId, location, from, to, Json.jsonb(rawRecord));
        return jdbc.queryForObject("SELECT id FROM catalog_entry_version WHERE entry_id=? AND version_no=?",
                Long.class, entryId, versionNo);
    }

    private long docId(String code) {
        return jdbc.queryForObject("SELECT id FROM catalog_document WHERE doc_code=?", Long.class, code);
    }

    private void attr(long versionId, String name, String normalized, String raw, Object detail,
                      boolean convertible, String docCode, String location) {
        jdbc.update("INSERT INTO semantic_attribute(entry_version_id, attr_name, normalized_value, raw_value, "
                + "attr_detail, convertible, source_doc_id, source_location) VALUES (?,?,?,?,?,?,?,?)",
                versionId, name, normalized, raw, Json.jsonb(detail == null ? Map.of() : detail),
                convertible, docId(docCode), location);
    }

    private void seedEntriesAndVersions() {
        OffsetDateTime nov1 = OffsetDateTime.parse("2026-11-01T00:00:00+08:00");
        OffsetDateTime oct31 = OffsetDateTime.parse("2026-10-31T23:59:59+08:00");

        // E1：干净基线，六类受控属性齐全且均有回链 → 可比较
        long e1 = entry("ENTRY-E1", "阿莫西林胶囊（供应商甲）", "供应商甲", "TGT-01");
        long v1 = version(e1, 1, docId("DOC-SUP-A"), "supplier-a/catalog-2026-11.xlsx#E1", nov1, null,
                Map.of("tradeName", "阿莫仙", "spec", "250mg/粒", "pack", "盒/24粒"));
        attr(v1, "DOSAGE_FORM", "CAPSULE", "胶囊剂", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#DOSAGE_FORM");
        attr(v1, "CONCENTRATION", "250 mg/粒", "250mg/粒", Map.of("value", 250, "unit", "mg/粒", "basis", "per_unit"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E1-conc");
        attr(v1, "PACKAGE_LEVEL", "盒/24粒", "每盒24粒", Map.of("level", "盒", "innerCount", 24, "innerUnit", "粒"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E1-pack");
        attr(v1, "ROUTE", "ORAL", "口服", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#ROUTE");
        attr(v1, "POPULATION_RESTRICTION", "成人；青霉素过敏者禁用", "成人，青霉素过敏禁用",
                Map.of("minAge", 18), true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E1-pop");
        attr(v1, "STERILITY_GRADE", "NON_STERILE", "非无菌制剂", Map.of("controlled", true),
                true, "DOC-PHARM-CUR", "chp/2025/vol1#sec-口服制剂");

        // E2：单位不可换算 —— 10 mg/mL 与 10 mg/支，且无包装体积
        long e2 = entry("ENTRY-E2", "注射用头孢唑林钠（供应商甲）", "供应商甲", "TGT-02");
        long v2 = version(e2, 1, docId("DOC-SUP-A"), "supplier-a/catalog-2026-11.xlsx#E2", nov1, null,
                Map.of("tradeName", "唑林针", "specA", "10mg/mL", "specB", "10mg/支"));
        attr(v2, "DOSAGE_FORM", "INJECTION", "注射剂", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#DOSAGE_FORM");
        attr(v2, "CONCENTRATION", "10 mg/mL", "10mg/mL", Map.of("value", 10, "unit", "mg/mL", "basis", "per_volume"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E2-conc-A");
        attr(v2, "CONCENTRATION", "10 mg/支", "10mg/支", Map.of("value", 10, "unit", "mg/支", "basis", "per_device",
                        "convertible", false, "reason", "缺少每支包装体积(mL)，无法换算为 mg/mL"),
                false, "DOC-SUP-B", "supplier-b/catalog-2026-11.pdf#p12-E2");
        Map<String, Object> e2PackDetail = new java.util.HashMap<>();
        e2PackDetail.put("level", "支");
        e2PackDetail.put("packageVolumeMl", null);
        attr(v2, "PACKAGE_LEVEL", "支", "单支装", e2PackDetail,
                false, "DOC-SUP-B", "supplier-b/catalog-2026-11.pdf#p12-E2-pack");
        attr(v2, "ROUTE", "INTRAVENOUS", "静脉注射", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#ROUTE");
        attr(v2, "POPULATION_RESTRICTION", "成人", "成人", Map.of("minAge", 18),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E2-pop");
        attr(v2, "STERILITY_GRADE", "STERILE", "无菌注射剂", Map.of("controlled", true),
                true, "DOC-PHARM-CUR", "chp/2025/vol1#sec-注射剂");

        // E3：同名异剂型 —— 同一商品名“同松”同时用于片剂与注射液，禁止按名称归并
        long e3 = entry("ENTRY-E3", "同松", "供应商甲", "TGT-03");
        long v3 = version(e3, 1, docId("DOC-SUP-A"), "supplier-a/catalog-2026-11.xlsx#E3-tablet", nov1, null,
                Map.of("tradeName", "同松", "form", "片剂", "spec", "5mg/片"));
        attr(v3, "DOSAGE_FORM", "TABLET", "片剂", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#DOSAGE_FORM");
        attr(v3, "CONCENTRATION", "5 mg/片", "5mg/片", Map.of("value", 5, "unit", "mg/片", "basis", "per_unit"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E3-conc");
        attr(v3, "PACKAGE_LEVEL", "盒/30片", "每盒30片", Map.of("level", "盒", "innerCount", 30, "innerUnit", "片"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E3-pack");
        attr(v3, "ROUTE", "ORAL", "口服", Map.of("controlled", true), true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E3-route");
        attr(v3, "POPULATION_RESTRICTION", "成人", "成人", Map.of("minAge", 18),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E3-pop");
        attr(v3, "STERILITY_GRADE", "NON_STERILE", "非无菌制剂", Map.of("controlled", true),
                true, "DOC-PHARM-CUR", "chp/2025/vol1#sec-口服制剂");

        long e3x = entry("ENTRY-E3X", "同松", "供应商甲", "TGT-03");
        long v3x = version(e3x, 1, docId("DOC-SUP-A"), "supplier-a/catalog-2026-11.xlsx#E3-injection", nov1, null,
                Map.of("tradeName", "同松", "form", "注射液", "spec", "5mg/2mL"));
        attr(v3x, "DOSAGE_FORM", "INJECTION", "注射液", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#DOSAGE_FORM");
        attr(v3x, "CONCENTRATION", "2.5 mg/mL", "5mg/2mL", Map.of("value", 2.5, "unit", "mg/mL", "basis", "per_volume"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E3X-conc");
        attr(v3x, "PACKAGE_LEVEL", "支/2mL", "安瓿支装", Map.of("level", "支", "innerUnit", "mL", "innerCount", 2),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E3X-pack");
        attr(v3x, "ROUTE", "INTRAVENOUS", "静脉注射", Map.of("controlled", true),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E3X-route");
        attr(v3x, "POPULATION_RESTRICTION", "成人", "成人", Map.of("minAge", 18),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E3X-pop");
        attr(v3x, "STERILITY_GRADE", "STERILE", "无菌注射剂", Map.of("controlled", true),
                true, "DOC-PHARM-CUR", "chp/2025/vol1#sec-注射剂");

        // E4：过期药典 —— 引用 CHP-2020（10-31 到期），修订通知未给替代依据
        long e4 = entry("ENTRY-E4", "盐酸利多卡因注射液（供应商甲）", "供应商甲", "TGT-04");
        long v4 = version(e4, 1, docId("DOC-PHARM-OLD"), "chp/2020/vol2#sec-利多卡因-E4", nov1, null,
                Map.of("tradeName", "利多针", "spec", "20mg/mL", "pharmacopoeiaRef", "CHP-2020",
                        "changeNotice", "DOC-SCN-E4"));
        attr(v4, "DOSAGE_FORM", "INJECTION", "注射剂", Map.of("controlled", true), true, "DOC-PHARM-OLD", "chp/2020/vol2#form");
        attr(v4, "CONCENTRATION", "20 mg/mL", "20mg/mL", Map.of("value", 20, "unit", "mg/mL", "basis", "per_volume"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E4-conc");
        attr(v4, "PACKAGE_LEVEL", "支/5mL", "安瓿支装", Map.of("level", "支", "innerUnit", "mL", "innerCount", 5),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E4-pack");
        attr(v4, "ROUTE", "INTRAVENOUS", "静脉注射", Map.of("controlled", true), true, "DOC-PHARM-OLD", "chp/2020/vol2#route");
        attr(v4, "POPULATION_RESTRICTION", "成人；严重传导阻滞患者禁用", "成人，严重传导阻滞禁用",
                Map.of("minAge", 18), true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E4-pop");
        attr(v4, "STERILITY_GRADE", "STERILE", "无菌注射剂", Map.of("controlled", true, "pharmacopoeiaRef", "CHP-2020"),
                true, "DOC-PHARM-OLD", "chp/2020/vol2#sterility");

        // E5：限制条件缺失 —— 声明要求辅料阈值，但来源只列成分名称、无含量数据
        long e5 = entry("ENTRY-E5", "二甲双胍片（供应商甲）", "供应商甲", "TGT-05");
        long v5 = version(e5, 1, docId("DOC-SUP-A"), "supplier-a/catalog-2026-11.xlsx#E5", nov1, null,
                Map.of("tradeName", "双胍片", "spec", "500mg/片", "excipients", List.of("硬脂酸镁", "聚维酮"),
                        "excipientContentProvided", false));
        attr(v5, "DOSAGE_FORM", "TABLET", "片剂", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#DOSAGE_FORM");
        attr(v5, "CONCENTRATION", "500 mg/片", "500mg/片", Map.of("value", 500, "unit", "mg/片", "basis", "per_unit"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E5-conc");
        attr(v5, "PACKAGE_LEVEL", "瓶/60片", "每瓶60片", Map.of("level", "瓶", "innerCount", 60, "innerUnit", "片"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E5-pack");
        attr(v5, "ROUTE", "ORAL", "口服", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#ROUTE");
        attr(v5, "POPULATION_RESTRICTION", "成人；肾功能不全需调整", "成人，肾功能不全调整",
                Map.of("minAge", 18), true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E5-pop");
        attr(v5, "STERILITY_GRADE", "NON_STERILE", "非无菌制剂", Map.of("controlled", true),
                true, "DOC-PHARM-CUR", "chp/2025/vol1#sec-口服制剂");
        attr(v5, "EXCIPIENT", "硬脂酸镁;聚维酮", "硬脂酸镁、聚维酮（仅列名称，未列含量）",
                Map.of("ingredients", List.of("硬脂酸镁", "聚维酮"), "contentProvided", false,
                        "limitRequiredPercent", 0.5, "limitSubject", "硬脂酸镁"),
                false, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E5-excipient");

        // E6：包装层级变更 —— 同一条目两个生效期间分别保留版本
        long e6 = entry("ENTRY-E6", "氯化钠注射液（供应商甲）", "供应商甲", "TGT-06");
        long v6a = version(e6, 1, docId("DOC-SUP-A"), "supplier-a/catalog-2026-10.xlsx#E6",
                OffsetDateTime.parse("2026-09-01T00:00:00+08:00"), oct31,
                Map.of("tradeName", "盐针", "spec", "9mg/mL", "pack", "盒/10支", "period", "2026-09~10"));
        attr(v6a, "DOSAGE_FORM", "INJECTION", "注射剂", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#DOSAGE_FORM");
        attr(v6a, "CONCENTRATION", "9 mg/mL", "9mg/mL", Map.of("value", 9, "unit", "mg/mL", "basis", "per_volume"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-10.xlsx#E6-conc");
        attr(v6a, "PACKAGE_LEVEL", "盒/10支", "每盒10支", Map.of("level", "盒", "innerCount", 10, "innerUnit", "支",
                        "changeNotice", "DOC-SCN-E6"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-10.xlsx#E6-pack");
        attr(v6a, "ROUTE", "INTRAVENOUS", "静脉注射", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#ROUTE");
        attr(v6a, "POPULATION_RESTRICTION", "不限人群", "不限人群", Map.of(),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-10.xlsx#E6-pop");
        attr(v6a, "STERILITY_GRADE", "STERILE", "无菌注射剂", Map.of("controlled", true),
                true, "DOC-PHARM-CUR", "chp/2025/vol1#sec-注射剂");

        long v6b = version(e6, 2, docId("DOC-SCN-E6"), "supplier-a/notice-SCN-E6.pdf#p1-E6", nov1, null,
                Map.of("tradeName", "盐针", "spec", "9mg/mL", "pack", "箱/100盒", "period", "2026-11~"));
        attr(v6b, "DOSAGE_FORM", "INJECTION", "注射剂", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#DOSAGE_FORM");
        attr(v6b, "CONCENTRATION", "9 mg/mL", "9mg/mL", Map.of("value", 9, "unit", "mg/mL", "basis", "per_volume"),
                true, "DOC-SUP-A", "supplier-a/catalog-2026-11.xlsx#E6-conc");
        attr(v6b, "PACKAGE_LEVEL", "箱/100盒", "每箱100盒（每盒10支）", Map.of("level", "箱", "innerCount", 100,
                        "innerUnit", "盒", "nestedInnerCount", 10, "changeNotice", "DOC-SCN-E6"),
                true, "DOC-SCN-E6", "supplier-a/notice-SCN-E6.pdf#p1-E6-pack");
        attr(v6b, "ROUTE", "INTRAVENOUS", "静脉注射", Map.of("controlled", true), true, "DOC-MDM", "mdm/spec-master#ROUTE");
        attr(v6b, "POPULATION_RESTRICTION", "不限人群", "不限人群", Map.of(),
                true, "DOC-SUP-A", "supplier-a/notice-SCN-E6.pdf#p1-E6-pop");
        attr(v6b, "STERILITY_GRADE", "STERILE", "无菌注射剂", Map.of("controlled", true),
                true, "DOC-PHARM-CUR", "chp/2025/vol1#sec-注射剂");
    }
}
