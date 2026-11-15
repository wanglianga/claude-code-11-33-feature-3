package com.trace.recall;

import com.fasterxml.jackson.databind.JsonNode;
import com.trace.recall.common.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替代目录语义对齐服务端到端测试（独立 schema recall_test）。
 * 验收样例：单位不可换算、同名异剂型、过期药典、限制条件缺失、包装层级变更，
 * 并验证属性级依据、可反驳反例、按分析日期选版本与发布条目全量下钻。
 */
class SubstitutionServiceE2ETest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "GET " + path + " 失败: " + res.body());
        return Json.MAPPER.readTree(res.body());
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(res.statusCode() >= 200 && res.statusCode() < 300,
                "POST " + path + " 失败: " + res.body());
        return Json.MAPPER.readTree(res.body());
    }

    private JsonNode statementForTarget(String targetCode) throws Exception {
        for (JsonNode s : get("/api/substitution/statements")) {
            if (targetCode.equals(s.get("targetProductCode").asText())) {
                return s;
            }
        }
        throw new AssertionError("缺少目标产品 " + targetCode + " 的声明");
    }

    private boolean hasConflict(JsonNode statement, String type) {
        for (JsonNode c : statement.get("conflicts")) {
            if (type.equals(c.get("conflictType").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode trial(JsonNode statement, String scenario) {
        for (JsonNode t : statement.get("trials")) {
            if (scenario.equals(t.get("scenarioType").asText())) {
                return t;
            }
        }
        return null;
    }

    @Test
    void scenario01_cleanBaselineIsComparableWithFullEvidence() throws Exception {
        JsonNode s = statementForTarget("TGT-01");
        assertEquals("COMPARABLE", s.get("verdict").asText());
        assertEquals("APPROVED", s.get("reviewStatus").asText());
        assertEquals(100.0, s.get("evidenceCompleteness").asDouble(), 0.01);
        // 每条依据都带属性名与来源回链
        for (JsonNode basis : s.get("basis")) {
            assertFalse(basis.get("sourceLocation").asText().isBlank());
            assertNotNull(basis.get("sourceDocCode"));
        }
    }

    @Test
    void scenario02_unitInconvertibleIsNotComparableAndBlocksTrials() throws Exception {
        JsonNode s = statementForTarget("TGT-02");
        assertEquals("NOT_COMPARABLE", s.get("verdict").asText());
        assertEquals("BLOCKED", s.get("reviewStatus").asText());
        assertTrue(hasConflict(s, "UNIT_INCONVERTIBLE"));
        // 10 mg/mL 与 10 mg/支 且无包装体积 → 字典保留不可换算标记
        boolean inconvertibleAttr = false;
        for (JsonNode entry : get("/api/substitution/entries")) {
            if (!"ENTRY-E2".equals(entry.get("entryCode").asText())) {
                continue;
            }
            for (JsonNode v : entry.get("versions")) {
                for (JsonNode a : v.get("attributes")) {
                    if ("CONCENTRATION".equals(a.get("attrName").asText())
                            && !a.get("convertible").asBoolean()) {
                        inconvertibleAttr = true;
                    }
                }
            }
        }
        assertTrue(inconvertibleAttr, "mg/支 浓度必须保留 convertible=false");
        JsonNode boundary = trial(s, "BOUNDARY_CONCENTRATION");
        assertEquals("UNVERIFIED", boundary.get("result").asText());
        assertTrue(boundary.get("blocksApproval").asBoolean());
    }

    @Test
    void scenario03_sameNameDifferentFormCreatesConflictForBothEntries() throws Exception {
        JsonNode tablet = null;
        JsonNode injection = null;
        for (JsonNode s : get("/api/substitution/statements")) {
            if (!"TGT-03".equals(s.get("targetProductCode").asText())) {
                continue;
            }
            if ("ENTRY-E3".equals(s.get("entryCode").asText())) {
                tablet = s;
            }
            if ("ENTRY-E3X".equals(s.get("entryCode").asText())) {
                injection = s;
            }
        }
        assertNotNull(tablet);
        assertNotNull(injection);
        assertEquals("NOT_COMPARABLE", tablet.get("verdict").asText());
        assertEquals("NOT_COMPARABLE", injection.get("verdict").asText());
        assertTrue(hasConflict(tablet, "SAME_NAME_DIFF_FORM"));
        assertTrue(hasConflict(injection, "SAME_NAME_DIFF_FORM"));
        // 反例：同名异剂型归并即越界
        JsonNode sameNameTrial = trial(tablet, "SAME_NAME_DIFF_FORM");
        assertEquals("FAIL", sameNameTrial.get("result").asText());
        assertTrue(sameNameTrial.get("refutesStatement").asBoolean());
        // 两个条目剂型受控值确实不同，证明没有按名称归并
        List<String> forms = new ArrayList<>();
        for (JsonNode e : get("/api/substitution/entries")) {
            if (e.get("entryCode").asText().startsWith("ENTRY-E3")) {
                for (JsonNode v : e.get("versions")) {
                    for (JsonNode a : v.get("attributes")) {
                        if ("DOSAGE_FORM".equals(a.get("attrName").asText())) {
                            forms.add(a.get("normalizedValue").asText());
                        }
                    }
                }
            }
        }
        assertTrue(forms.contains("TABLET") && forms.contains("INJECTION"));
    }

    @Test
    void scenario04_expiredPharmacopoeiaDowngradesToPendingReview() throws Exception {
        JsonNode s = statementForTarget("TGT-04");
        assertEquals("PENDING_REVIEW", s.get("verdict").asText());
        assertEquals("PENDING_REVIEW", s.get("reviewStatus").asText());
        assertTrue(hasConflict(s, "EXPIRED_PHARMACOPOEIA"));
        // 修订通知没有替代依据 → 不能沿用旧结论
        boolean noReplacement = false;
        for (JsonNode c : s.get("conflicts")) {
            if ("EXPIRED_PHARMACOPOEIA".equals(c.get("conflictType").asText())) {
                String refs = c.get("sourceRefs").toString();
                assertTrue(refs.contains("\"replacementBasisProvided\":false"));
                noReplacement = true;
            }
        }
        assertTrue(noReplacement);
    }

    @Test
    void scenario05_missingExcipientContentBlocksConditionalStatement() throws Exception {
        JsonNode s = statementForTarget("TGT-05");
        assertEquals("CONDITIONALLY_COMPARABLE", s.get("verdict").asText());
        assertEquals("BLOCKED", s.get("reviewStatus").asText());
        assertTrue(hasConflict(s, "MISSING_RESTRICTION"));
        JsonNode conditional = trial(s, "CONDITIONAL_RESTRICTION");
        assertEquals("UNVERIFIED", conditional.get("result").asText(), "无含量数据时反例未证实，不得通过");
        assertTrue(conditional.get("blocksApproval").asBoolean());
        // 阻断反例存在时，人工 APPROVE 必须被拒绝
        long statementId = s.get("id").asLong();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/api/substitution/statements/" + statementId + "/review"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"decision\":\"APPROVE\",\"reviewer\":\"审核员\",\"reviewNote\":\"尝试通过\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, res.statusCode(), "存在阻断反例时审核通过应被拒绝");
    }

    @Test
    void scenario06_packageLevelChangeSelectsVersionByAnalysisDate() throws Exception {
        JsonNode requests = get("/api/substitution/requests");
        long historicalId = -1;
        long currentId = -1;
        for (JsonNode r : requests) {
            String d = r.get("analysisDate").asText();
            if (d.startsWith("2026-10-15")) {
                historicalId = r.get("id").asLong();
            }
            if (d.startsWith("2026-11-15")) {
                currentId = r.get("id").asLong();
            }
        }
        assertTrue(historicalId > 0 && currentId > 0);

        String historicalPack = packageLevelOfTarget(historicalId);
        String currentPack = packageLevelOfTarget(currentId);
        assertEquals("盒/10支", historicalPack, "历史分析日期必须命中旧包装层级版本");
        assertEquals("箱/100盒", currentPack, "当前分析日期必须命中新包装层级版本，不得用当前包装覆盖历史声明");
    }

    @Test
    void scenario07_publishedItemDrilldownIsComplete() throws Exception {
        JsonNode requests = get("/api/substitution/requests");
        long currentId = requests.get(requests.size() - 1).get("id").asLong();
        JsonNode detail = get("/api/substitution/requests/" + currentId);
        for (JsonNode item : detail.get("items")) {
            JsonNode drill = item.get("drilldown");
            assertTrue(drill.has("sourceDocuments"), "可展开到原始目录位置");
            assertTrue(drill.has("controlledAttributes"), "可展开到受控属性");
            assertTrue(drill.has("statement"), "可展开到适用性声明");
            assertTrue(drill.has("counterexampleTrials"), "可展开到反例试验");
            assertNotNull(drill.get("statement").get("reviewStatus"), "可展开到审核决定");
            // 每个受控属性都有来源回链
            for (JsonNode a : drill.get("controlledAttributes")) {
                assertFalse(a.get("sourceLocation").asText().isBlank());
            }
        }
    }

    private String packageLevelOfTarget(long requestId) throws Exception {
        JsonNode detail = get("/api/substitution/requests/" + requestId);
        for (JsonNode item : detail.get("items")) {
            if ("TGT-06".equals(item.get("targetProductCode").asText())) {
                for (JsonNode a : item.get("drilldown").get("controlledAttributes")) {
                    if ("PACKAGE_LEVEL".equals(a.get("attrName").asText())) {
                        return a.get("normalizedValue").asText();
                    }
                }
            }
        }
        throw new AssertionError("请求 " + requestId + " 缺少 TGT-06 包装层级");
    }
}
