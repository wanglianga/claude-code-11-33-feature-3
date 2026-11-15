package com.trace.recall;

import com.fasterxml.jackson.databind.JsonNode;
import com.trace.recall.common.Json;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召回域端到端规则测试（独立 schema recall_test），覆盖 README 第 6 节七条断言。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecallServiceE2ETest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private static long runId1;
    private static long runId2;

    private JsonNode post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "POST " + path + " 失败: " + res.body());
        return Json.MAPPER.readTree(res.body());
    }

    private JsonNode get(String path, String apiKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        if (apiKey != null) {
            b.header("X-API-Key", apiKey);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return Json.MAPPER.readTree(res.body());
    }

    private int status(String path, String apiKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        if (apiKey != null) {
            b.header("X-API-Key", apiKey);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @Order(1)
    void rule01_identityAmbiguityKeepsCandidatesAndNeverMerges() throws Exception {
        JsonNode r1 = post("/api/runs", """
                {"inspectionCorrectionVersion":"QC-REV-1","cutoffAt":"2026-11-10T18:00:00+08:00","createdBy":"测试员"}""");
        runId1 = r1.get("runId").asLong();
        JsonNode detail = get("/api/runs/" + runId1, null);

        long ambiguous = detail.get("identityRecords").findValuesAsText("matchStatus").stream()
                .filter("AMBIGUOUS"::equals).count();
        assertTrue(ambiguous >= 2, "RM-A02 的两个无凭证供应商批号都应保留为歧义");

        // 谱系边只按内部批号建立，供应商批号不得出现合并边
        Set<String> edgeBatches = new LinkedHashSet<>();
        detail.get("edges").forEach(e -> {
            edgeBatches.add(e.get("upstreamBatch").isNull() ? "" : e.get("upstreamBatch").asText());
            edgeBatches.add(e.get("downstreamBatch").asText());
        });
        assertFalse(edgeBatches.contains("SUP-LOT-22"));
        assertFalse(edgeBatches.contains("SUP-LOT-23"));
    }

    @Test
    @Order(2)
    void rule02_overFeedingFreezesChainAndZeroesGrandchildConfirmed() throws Exception {
        JsonNode detail = get("/api/runs/" + runId1, null);
        JsonNode rmA04 = null;
        for (JsonNode q : detail.get("quantities")) {
            if ("RM-A04".equals(q.get("batchCode").asText())) {
                rmA04 = q;
            }
        }
        assertTrue(rmA04 != null);
        assertEquals(60.0, rmA04.get("confirmedLower").asDouble(), 0.001);
        assertEquals(15.0, rmA04.get("unattributedDelta").asDouble(), 0.001, "超额 75-60=15kg 无法归因");

        long frozen = detail.get("edges").findValuesAsText("confidenceStatus").stream()
                .filter("FROZEN"::equals).count();
        assertTrue(frozen >= 3, "拆分边与下游放行边均应冻结，实际冻结 " + frozen);

        JsonNode fgC03 = null;
        for (JsonNode q : detail.get("quantities")) {
            if ("FG-C03".equals(q.get("batchCode").asText())) {
                fgC03 = q;
            }
        }
        assertTrue(fgC03 != null);
        assertEquals(0.0, fgC03.get("confirmedLower").asDouble(), 0.001, "孙代确认量必须冻结为 0");
        assertEquals(30.0, fgC03.get("possibleUpper").asDouble(), 0.001);
    }

    @Test
    @Order(3)
    void rule03_quantityConflictKeepsBothNumbers() throws Exception {
        JsonNode trace = get("/api/runs/" + runId1 + "/batches/FG-C03/trace", null);
        JsonNode nodes = trace.get("quantity").get("detail").get("nodes");
        JsonNode hospB = null;
        for (JsonNode n : nodes) {
            if ("HOSP-乙".equals(n.get("node").asText())) {
                hospB = n;
            }
        }
        assertTrue(hospB != null);
        assertTrue(hospB.get("quantityConflict").asBoolean());
        assertEquals(50.0, hospB.get("logisticsSignedQty").asDouble(), 0.001);
        assertEquals(46.0, hospB.get("institutionReceivedQty").asDouble(), 0.001);
    }

    @Test
    @Order(4)
    void rule04_duplicateBoxDegradesBothShipmentEdges() throws Exception {
        JsonNode detail = get("/api/runs/" + runId1, null);
        long duplicateEdges = detail.get("edges").findValuesAsText("blockReason").stream()
                .filter("DUPLICATE_BOX"::equals).count();
        assertEquals(2, duplicateEdges, "BOX-9 两条互不相连运输边都应降级为重复载体证据");
    }

    @Test
    @Order(5)
    void rule05_parseDiffOnlyDegradesLogisticsNotMesFeeding() throws Exception {
        JsonNode detail = get("/api/runs/" + runId1, null);
        boolean logisticsDegraded = false;
        boolean mesFeedingConfirmed = false;
        for (JsonNode e : detail.get("edges")) {
            String type = e.get("relationType").asText();
            String status = e.get("confidenceStatus").asText();
            String pkg = e.get("sourcePackageCode").asText();
            if ("SHIPMENT".equals(type) && "PKG-LOG-2".equals(pkg)) {
                logisticsDegraded = "SUSPECTED".equals(status);
            }
            if ("FEEDING".equals(type) && "PKG-MES-1".equals(pkg) && !"F-FEED-3".equals(e.get("edgeCode").asText())) {
                mesFeedingConfirmed = "CONFIRMED".equals(status);
            }
        }
        assertTrue(logisticsDegraded, "PARSE_DIFF 应只降级 LOGISTICS 边");
        assertTrue(mesFeedingConfirmed, "MES 的 FEEDING 边不应被解析差异连坐");
    }

    @Test
    @Order(6)
    void rule06_snapshotSizesAndVersionedReleaseDiff() throws Exception {
        JsonNode d1 = get("/api/runs/" + runId1, null);
        assertEquals(6, d1.get("packages").size(), "截止 #1 快照应为 6 个包");

        JsonNode r2 = post("/api/runs", """
                {"inspectionCorrectionVersion":"QC-REV-2","cutoffAt":"2026-11-14T18:00:00+08:00","createdBy":"测试员"}""");
        runId2 = r2.get("runId").asLong();
        JsonNode d2 = get("/api/runs/" + runId2, null);
        assertEquals(8, d2.get("packages").size(), "截止 #2 快照应为 8 个包");

        post("/api/runs/" + runId1 + "/publish", "{}");
        post("/api/runs/" + runId2 + "/publish", "{}");
        JsonNode releases = get("/api/releases", null);
        String diffType = null;
        boolean oldSuperseded = false;
        for (JsonNode r : releases) {
            if ("SUPERSEDED".equals(r.get("status").asText())) {
                oldSuperseded = true;
            }
            if ("PUBLISHED".equals(r.get("status").asText())) {
                diffType = r.get("diffType").asText();
            }
        }
        assertTrue(oldSuperseded, "旧发布版本必须只读保留为 SUPERSEDED");
        assertTrue(diffType != null && diffType.contains("INSPECTION_CHANGED")
                && diffType.contains("NEW_LOGISTICS_EVIDENCE")
                && diffType.contains("PARSE_RULE_REVISED"), "新版本应同时包含三类变化: " + diffType);
    }

    @Test
    @Order(7)
    void rule07_externalApiAuthAndFieldWhitelist() throws Exception {
        assertEquals(401, status("/external/releases", null), "无 Key 必须 401");
        assertEquals(401, status("/external/releases", "wrong-key"), "错 Key 必须 401");

        JsonNode releases = get("/external/releases", "ext-trace-key-2026");
        String publishedCode = null;
        for (JsonNode r : releases) {
            if ("PUBLISHED".equals(r.get("status").asText())) {
                publishedCode = r.get("releaseCode").asText();
            }
        }
        JsonNode summary = get("/external/releases/" + publishedCode, "ext-trace-key-2026");
        String raw = summary.toString();
        for (String banned : new String[]{"payload", "sensitiveContacts", "contactPerson", "phone"}) {
            assertFalse(raw.contains(banned), "外部响应不得包含敏感字段: " + banned);
        }
        Set<String> allowed = Set.of("batchCode", "nodeName", "confirmedQty", "pendingQty", "possibleQty",
                "lowerBound", "upperBound", "unit", "evidenceGrade", "traceLink", "conflictFlag");
        for (JsonNode item : summary.get("items")) {
            item.fieldNames().forEachRemaining(f -> assertTrue(allowed.contains(f), "非白名单字段: " + f));
        }
    }
}
