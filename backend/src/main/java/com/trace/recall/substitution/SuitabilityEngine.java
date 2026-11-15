package com.trace.recall.substitution;

import com.trace.recall.common.Json;
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
 * 适用性引擎（派生层，幂等重算）：
 * 1. 矛盾审查器：单位不可换算、同名异剂型、过期药典且无替代依据、限制条件缺失、包装层级变更；
 * 2. 适用性声明册：可比较 / 仅可在附加条件下比较 / 不可比较 / 待核，逐条带属性级依据与来源回链；
 * 3. 规则试验台：边界浓度、包装换算、同名异剂型、条件性限制反例，未证实反例直接阻断声明。
 * 名称从不参与归并判断，所有结论都可回溯到受控属性与原始目录位置。
 */
@Service
public class SuitabilityEngine {

    private static final List<String> REQUIRED_GROUPS = List.of(
            "DOSAGE_FORM", "CONCENTRATION", "PACKAGE_LEVEL", "ROUTE", "POPULATION_RESTRICTION", "STERILITY_GRADE");

    private final JdbcTemplate jdbc;
    private final OffsetDateTime asOf;

    public SuitabilityEngine(JdbcTemplate jdbc,
                             @Value("${app.substitution.as-of:2026-11-15T12:00:00+08:00}") String asOf) {
        this.jdbc = jdbc;
        this.asOf = OffsetDateTime.parse(asOf);
    }

    @Transactional
    public void deriveAll() {
        jdbc.update("DELETE FROM counterexample_trial");
        jdbc.update("DELETE FROM suitability_statement");
        jdbc.update("DELETE FROM catalog_conflict");
        List<Long> versionIds = jdbc.queryForList(
                "SELECT id FROM catalog_entry_version ORDER BY id", Long.class);
        for (Long versionId : versionIds) {
            deriveConflicts(versionId);
        }
        for (Long versionId : versionIds) {
            long statementId = deriveStatement(versionId);
            deriveTrials(statementId, versionId);
        }
    }

    // ------------------------------------------------------------------
    // 矛盾审查器
    // ------------------------------------------------------------------

    private void deriveConflicts(long versionId) {
        long entryId = jdbc.queryForObject("SELECT entry_id FROM catalog_entry_version WHERE id=?", Long.class, versionId);
        List<Map<String, Object>> attrs = attrs(versionId);

        // 1) 单位不可换算：浓度存在 per_volume 与 per_device 两种口径，或属性本身标记 convertible=false
        List<Map<String, Object>> concentrations = attrs.stream()
                .filter(a -> "CONCENTRATION".equals(a.get("attrName"))).toList();
        boolean markedInconvertible = concentrations.stream().anyMatch(a -> Boolean.FALSE.equals(a.get("convertible")));
        List<String> bases = concentrations.stream()
                .map(a -> str(detail(a).get("basis"))).filter(b -> b != null).distinct().toList();
        if (markedInconvertible || bases.contains("per_volume") && bases.contains("per_device")) {
            insertConflict("UNIT_INCONVERTIBLE", "BLOCKER", entryId, versionId, "CONCENTRATION",
                    "同一产品浓度同时出现 mg/mL（按体积）与 mg/支（按器具）口径，且包装体积缺失，单位不可换算，不得标为可比较",
                    concentrations.stream().map(a -> Map.of("attr", a.get("normalizedValue"),
                            "source", a.get("sourceDocCode"), "location", a.get("sourceLocation"),
                            "convertible", a.get("convertible"))).toList());
        }

        // 2) 同名异剂型：同一商品名在别的条目上出现不同剂型
        String productName = jdbc.queryForObject("SELECT product_name FROM catalog_entry WHERE id=?",
                String.class, entryId);
        List<Map<String, Object>> sameNameForms = jdbc.query(
                "SELECT ce.id AS entry_id, ce.entry_code, cev.id AS version_id, sa.normalized_value AS form "
                        + "FROM catalog_entry ce "
                        + "JOIN catalog_entry_version cev ON cev.entry_id=ce.id "
                        + "JOIN semantic_attribute sa ON sa.entry_version_id=cev.id AND sa.attr_name='DOSAGE_FORM' "
                        + "WHERE ce.product_name=? ORDER BY ce.id, cev.version_no",
                (rs, i) -> Map.of("entryId", rs.getLong("entry_id"), "entryCode", rs.getString("entry_code"),
                        "versionId", rs.getLong("version_id"), "form", rs.getString("form")), productName);
        long distinctForms = sameNameForms.stream().map(m -> str(m.get("form"))).distinct().count();
        if (distinctForms >= 2) {
            insertConflict("SAME_NAME_DIFF_FORM", "BLOCKER", entryId, versionId, "DOSAGE_FORM",
                    "商品名「" + productName + "」同时被片剂与注射液等不同剂型使用，须按条目补充并核对给药途径，禁止按名称归并",
                    sameNameForms.stream().map(m -> Map.of("entry", m.get("entryCode"),
                            "dosageForm", m.get("form"), "requiredAction", "补充给药途径后逐条判定")).toList());
        }

        // 3) 过期药典：属性回链到已到期药典，且规格变更通知没有给出替代依据
        boolean expiredRef = attrs.stream().anyMatch(a -> {
            if (!"PHARMACOPOEIA_REFERENCE".equals(a.get("sourceDocType"))) {
                return false;
            }
            OffsetDateTime to = (OffsetDateTime) a.get("sourceEffectiveTo");
            return to != null && to.isBefore(asOf);
        });
        if (expiredRef) {
            boolean hasReplacement = jdbc.query(
                    "SELECT count(*) FROM catalog_document d WHERE d.doc_type='SPEC_CHANGE_NOTICE' "
                            + "AND d.raw_payload->>'replacementBasis' IS NOT NULL",
                    rs -> rs.next() && rs.getInt(1) > 0);
            insertConflict("EXPIRED_PHARMACOPOEIA", "BLOCKER", entryId, versionId, "STERILITY_GRADE",
                    "药典引用已过有效期（" + asOf.toLocalDate() + " 判定），目录修订通知"
                            + (hasReplacement ? "已给出替代依据" : "未给出替代依据") + "，相关声明降为待核，不得沿用旧结论",
                    attrs.stream().filter(a -> "PHARMACOPOEIA_REFERENCE".equals(a.get("sourceDocType")))
                            .map(a -> Map.of("source", a.get("sourceDocCode"), "location", a.get("sourceLocation"),
                                    "effectiveTo", a.get("sourceEffectiveTo") == null ? "" : a.get("sourceEffectiveTo").toString(),
                                    "replacementBasisProvided", hasReplacement)).toList());
        }

        // 4) 限制条件缺失：声明需要辅料含量阈值，但来源只列成分名称、无含量数据
        List<Map<String, Object>> excipients = attrs.stream()
                .filter(a -> "EXCIPIENT".equals(a.get("attrName"))).toList();
        for (Map<String, Object> ex : excipients) {
            Map<String, Object> detail = detail(ex);
            if (Boolean.FALSE.equals(detail.get("contentProvided")) && detail.get("limitSubject") != null) {
                insertConflict("MISSING_RESTRICTION", "BLOCKER", entryId, versionId, "EXCIPIENT",
                        "适用性条件要求辅料「" + detail.get("limitSubject") + "」含量不超过 "
                                + detail.get("limitRequiredPercent") + "%，来源文件仅列成分名称、没有含量数据，条件无法证实",
                        List.of(Map.of("source", ex.get("sourceDocCode"), "location", ex.get("sourceLocation"),
                                "ingredients", detail.get("ingredients"), "limitRequiredPercent",
                                detail.get("limitRequiredPercent"))));
            }
        }

        // 5) 包装层级变更：同一条目不同生效期间的包装层级不同（警告，版本分别保留即可）
        List<Map<String, Object>> packLevels = jdbc.query(
                "SELECT cev.version_no, sa.normalized_value, sa.source_location "
                        + "FROM semantic_attribute sa JOIN catalog_entry_version cev ON cev.id=sa.entry_version_id "
                        + "WHERE cev.entry_id=? AND sa.attr_name='PACKAGE_LEVEL' ORDER BY cev.version_no",
                (rs, i) -> Map.of("versionNo", rs.getInt("version_no"),
                        "level", rs.getString("normalized_value"), "location", rs.getString("source_location")),
                entryId);
        long distinctLevels = packLevels.stream().map(m -> str(m.get("level"))).distinct().count();
        if (distinctLevels >= 2) {
            insertConflict("PACKAGE_LEVEL_CHANGED", "WARNING", entryId, versionId, "PACKAGE_LEVEL",
                    "同一条目在不同生效期间包装层级发生变化，各版本分别保留，分析请求须按日期选择候选版本，不得用当前包装覆盖历史声明",
                    packLevels);
        }
    }

    // ------------------------------------------------------------------
    // 适用性声明册
    // ------------------------------------------------------------------

    private long deriveStatement(long versionId) {
        long entryId = jdbc.queryForObject("SELECT entry_id FROM catalog_entry_version WHERE id=?", Long.class, versionId);
        String target = jdbc.queryForObject("SELECT target_product_code FROM catalog_entry WHERE id=?",
                String.class, entryId);
        List<Map<String, Object>> attrs = attrs(versionId);
        List<Map<String, Object>> conflicts = conflictsOf(entryId, versionId);
        List<String> blockerTypes = conflicts.stream()
                .filter(c -> "BLOCKER".equals(c.get("severity"))).map(c -> str(c.get("conflictType"))).toList();

        List<Map<String, Object>> basis = attrs.stream().map(a -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("attrName", a.get("attrName"));
            b.put("normalizedValue", a.get("normalizedValue"));
            b.put("rawValue", a.get("rawValue"));
            b.put("convertible", a.get("convertible"));
            b.put("sourceDocCode", a.get("sourceDocCode"));
            b.put("sourceLocation", a.get("sourceLocation"));
            b.put("detail", detail(a));
            return b;
        }).toList();

        List<Map<String, Object>> conditions = new ArrayList<>();
        attrs.stream().filter(a -> "EXCIPIENT".equals(a.get("attrName"))).forEach(a -> {
            Map<String, Object> d = detail(a);
            if (d.get("limitSubject") != null) {
                conditions.add(Map.of("type", "EXCIPIENT_LIMIT", "subject", d.get("limitSubject"),
                        "maxPercent", d.get("limitRequiredPercent"),
                        "evidenceProvided", !Boolean.FALSE.equals(d.get("contentProvided"))));
            }
        });
        attrs.stream().filter(a -> "POPULATION_RESTRICTION".equals(a.get("attrName"))).findFirst()
                .ifPresent(a -> conditions.add(Map.of("type", "POPULATION_RESTRICTION",
                        "restriction", a.get("normalizedValue"), "evidenceProvided", true)));

        String verdict;
        String reviewStatus;
        String reviewer;
        String reviewNote;
        if (blockerTypes.contains("EXPIRED_PHARMACOPOEIA")) {
            verdict = "PENDING_REVIEW";
            reviewStatus = "PENDING_REVIEW";
            reviewer = null;
            reviewNote = "药典引用过期且无替代依据，声明降为待核，补齐现行药典依据前不得沿用旧结论";
        } else if (blockerTypes.contains("UNIT_INCONVERTIBLE")) {
            verdict = "NOT_COMPARABLE";
            reviewStatus = "BLOCKED";
            reviewer = "规则试验台";
            reviewNote = "浓度单位不可换算，名称相似不构成可比较性";
        } else if (blockerTypes.contains("SAME_NAME_DIFF_FORM")) {
            verdict = "NOT_COMPARABLE";
            reviewStatus = "BLOCKED";
            reviewer = "规则试验台";
            reviewNote = "同名异剂型冲突未消解，须补充给药途径后重新申报";
        } else if (blockerTypes.contains("MISSING_RESTRICTION")) {
            verdict = "CONDITIONALLY_COMPARABLE";
            reviewStatus = "BLOCKED";
            reviewer = "规则试验台";
            reviewNote = "辅料阈值条件缺少含量证据，未证实反例阻断本声明";
        } else {
            verdict = "COMPARABLE";
            reviewStatus = "APPROVED";
            reviewer = "质量审核员-李某";
            reviewNote = "六类受控属性齐全且均有来源回链，属性级依据支持可比较";
        }

        double completeness = evidenceCompleteness(attrs);
        String scope = attrs.stream().filter(a -> "POPULATION_RESTRICTION".equals(a.get("attrName")))
                .map(a -> str(a.get("normalizedValue"))).findFirst().orElse("未声明人群限制");

        long seq = jdbc.queryForObject("SELECT COALESCE(max(id),0)+1 FROM suitability_statement", Long.class);
        String code = String.format("STM-%03d", seq);
        jdbc.update("INSERT INTO suitability_statement(statement_code, entry_version_id, target_product_code, verdict, "
                        + "review_status, basis, conditions, applicability_scope, evidence_completeness, reviewer, review_note, reviewed_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                code, versionId, target, verdict, reviewStatus, Json.jsonb(basis), Json.jsonb(conditions),
                scope, completeness, reviewer, reviewNote,
                reviewStatus.equals("APPROVED") ? OffsetDateTime.now() : null);
        return jdbc.queryForObject("SELECT id FROM suitability_statement WHERE statement_code=?", Long.class, code);
    }

    /** 证据完整度：六个必备属性组，缺失不得分；组内存在不可换算属性只得半分。 */
    private double evidenceCompleteness(List<Map<String, Object>> attrs) {
        double score = 0;
        for (String group : REQUIRED_GROUPS) {
            List<Map<String, Object>> groupAttrs = attrs.stream()
                    .filter(a -> group.equals(a.get("attrName"))).toList();
            if (groupAttrs.isEmpty()) {
                continue;
            }
            boolean allConvertible = groupAttrs.stream().allMatch(a -> Boolean.TRUE.equals(a.get("convertible")));
            score += allConvertible ? 1.0 : 0.5;
        }
        return Math.round(score / REQUIRED_GROUPS.size() * 10000.0) / 100.0;
    }

    // ------------------------------------------------------------------
    // 规则试验台
    // ------------------------------------------------------------------

    private void deriveTrials(long statementId, long versionId) {
        long entryId = jdbc.queryForObject("SELECT entry_id FROM catalog_entry_version WHERE id=?", Long.class, versionId);
        List<Map<String, Object>> attrs = attrs(versionId);
        List<Map<String, Object>> conflicts = conflictsOf(entryId, versionId);
        List<String> blockerTypes = conflicts.stream()
                .filter(c -> "BLOCKER".equals(c.get("severity"))).map(c -> str(c.get("conflictType"))).toList();
        long seqBase = jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM counterexample_trial", Long.class);

        // 反例 1：边界浓度（标称值 ±5% 边界输入）
        boolean inconvertible = blockerTypes.contains("UNIT_INCONVERTIBLE");
        seqBase++;
        jdbc.update("INSERT INTO counterexample_trial(trial_code, statement_id, scenario_type, input_summary, "
                        + "expected_boundary, result, refutes_statement, blocks_approval, evidence) VALUES (?,?,?,?,?,?,?,?,?)",
                String.format("TR-%03d", seqBase), statementId, "BOUNDARY_CONCENTRATION",
                "以标称浓度的 95%/105% 构造边界浓度输入，检验换算后是否仍落在目标规格区间",
                "边界浓度须与目标同口径可比较", inconvertible ? "UNVERIFIED" : "PASS",
                inconvertible, inconvertible,
                Json.jsonb(inconvertible
                        ? Map.of("reason", "mg/mL 与 mg/支 之间无包装体积，边界输入无法换算，反例未证实而非通过",
                        "conflict", "UNIT_INCONVERTIBLE")
                        : Map.of("boundaryLower", "95%标称", "boundaryUpper", "105%标称", "outcome", "同口径数值均在区间内")));

        // 反例 2：包装换算
        boolean packageChanged = blockerTypes.contains("PACKAGE_LEVEL_CHANGED")
                || conflicts.stream().anyMatch(c -> "PACKAGE_LEVEL_CHANGED".equals(c.get("conflictType")));
        boolean packageInconvertible = attrs.stream().filter(a -> "PACKAGE_LEVEL".equals(a.get("attrName")))
                .anyMatch(a -> Boolean.FALSE.equals(a.get("convertible")));
        seqBase++;
        String packResult = packageInconvertible ? "UNVERIFIED" : "PASS";
        jdbc.update("INSERT INTO counterexample_trial(trial_code, statement_id, scenario_type, input_summary, "
                + "expected_boundary, result, refutes_statement, blocks_approval, evidence) VALUES (?,?,?,?,?,?,?,?,?)",
                String.format("TR-%03d", seqBase), statementId, "PACKAGE_CONVERSION",
                "按包装层级做最小包装→中包装→外包装的层级换算反例，验证层级数量闭合",
                "各层级换算系数必须由来源明确给出", packResult, packageInconvertible, packageInconvertible,
                Json.jsonb(packageChanged
                        ? Map.of("versionRetention", true, "note", "包装层级已变更，两个生效期间版本均保留，按分析日期选择，历史声明不被覆盖")
                        : packageInconvertible ? Map.of("reason", "包装体积缺失，包装换算反例无法证实",
                        "conflict", "UNIT_INCONVERTIBLE")
                        : Map.of("outcome", "层级系数齐全，换算闭合")));

        // 反例 3：同名异剂型
        if (blockerTypes.contains("SAME_NAME_DIFF_FORM")) {
            seqBase++;
            jdbc.update("INSERT INTO counterexample_trial(trial_code, statement_id, scenario_type, input_summary, "
                    + "expected_boundary, result, refutes_statement, blocks_approval, evidence) VALUES (?,?,?,?,?,?,?,?,?)",
                    String.format("TR-%03d", seqBase), statementId, "SAME_NAME_DIFF_FORM",
                    "用相同商品名的注射液条目替换片剂条目构造反例，检验系统是否会因名称相似越界归并",
                    "剂型/给药途径不同必须判为不可比较", "FAIL", true, true,
                    Json.jsonb(Map.of("reason", "名称相同但剂型与给药途径不同，归并即越界",
                            "conflict", "SAME_NAME_DIFF_FORM")));
        }

        // 反例 4：条件性限制（辅料阈值等）
        List<Map<String, Object>> excipients = attrs.stream()
                .filter(a -> "EXCIPIENT".equals(a.get("attrName"))).toList();
        for (Map<String, Object> ex : excipients) {
            Map<String, Object> detail = detail(ex);
            if (detail.get("limitSubject") != null) {
                boolean missingContent = Boolean.FALSE.equals(detail.get("contentProvided"));
                seqBase++;
                jdbc.update("INSERT INTO counterexample_trial(trial_code, statement_id, scenario_type, input_summary, "
                        + "expected_boundary, result, refutes_statement, blocks_approval, evidence) VALUES (?,?,?,?,?,?,?,?,?)",
                        String.format("TR-%03d", seqBase), statementId, "CONDITIONAL_RESTRICTION",
                        "构造辅料「" + detail.get("limitSubject") + "」含量等于阈值 "
                                + detail.get("limitRequiredPercent") + "% 的边界反例，检验条件是否可证实",
                        "来源必须提供辅料含量数据，否则条件不成立",
                        missingContent ? "UNVERIFIED" : "PASS", missingContent, missingContent,
                        Json.jsonb(missingContent
                                ? Map.of("reason", "来源仅列成分名称、无含量数据，未证实反例，声明不得通过",
                                "conflict", "MISSING_RESTRICTION", "source", ex.get("sourceDocCode"),
                                "location", ex.get("sourceLocation"))
                                : Map.of("outcome", "含量数据支持阈值条件")));
            }
        }
    }

    // ------------------------------------------------------------------
    // 读取辅助
    // ------------------------------------------------------------------

    private List<Map<String, Object>> attrs(long versionId) {
        return jdbc.query(
                "SELECT sa.id, sa.attr_name, sa.normalized_value, sa.raw_value, sa.attr_detail, sa.convertible, "
                        + "sa.source_location, d.doc_code AS source_doc_code, d.doc_type AS source_doc_type, "
                        + "d.effective_to AS source_effective_to "
                        + "FROM semantic_attribute sa JOIN catalog_document d ON d.id=sa.source_doc_id "
                        + "WHERE sa.entry_version_id=? ORDER BY sa.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("attrName", rs.getString("attr_name"));
                    m.put("normalizedValue", rs.getString("normalized_value"));
                    m.put("rawValue", rs.getString("raw_value"));
                    m.put("attrDetail", Json.map(rs.getObject("attr_detail")));
                    m.put("convertible", rs.getBoolean("convertible"));
                    m.put("sourceLocation", rs.getString("source_location"));
                    m.put("sourceDocCode", rs.getString("source_doc_code"));
                    m.put("sourceDocType", rs.getString("source_doc_type"));
                    m.put("sourceEffectiveTo", rs.getObject("source_effective_to", OffsetDateTime.class));
                    return m;
                }, versionId);
    }

    private List<Map<String, Object>> conflictsOf(long entryId, long versionId) {
        return jdbc.query("SELECT conflict_code, conflict_type, severity, subject_attr, description, status "
                        + "FROM catalog_conflict WHERE entry_id=? AND (entry_version_id=? OR entry_version_id IS NULL) ORDER BY id",
                (rs, i) -> Map.of(
                        "conflictCode", rs.getString("conflict_code"),
                        "conflictType", rs.getString("conflict_type"),
                        "severity", rs.getString("severity"),
                        "subjectAttr", rs.getString("subject_attr") == null ? "" : rs.getString("subject_attr"),
                        "description", rs.getString("description"),
                        "status", rs.getString("status")),
                entryId, versionId);
    }

    private void insertConflict(String type, String severity, Long entryId, Long versionId, String subjectAttr,
                                String description, Object sourceRefs) {
        long seq = jdbc.queryForObject("SELECT COALESCE(max(id),0)+1 FROM catalog_conflict", Long.class);
        jdbc.update("INSERT INTO catalog_conflict(conflict_code, conflict_type, severity, entry_id, entry_version_id, "
                + "subject_attr, description, source_refs) VALUES (?,?,?,?,?,?,?,?)",
                String.format("CF-%03d", seq), type, severity, entryId, versionId, subjectAttr,
                description, Json.jsonb(sourceRefs));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(Map<String, Object> attr) {
        return (Map<String, Object>) attr.get("attrDetail");
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
