package com.trace.recall.ingestion;

import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入登记台查询服务：来源包、解析规则、检验版本、批次主数据。
 * 解析状态在接收时固化；哈希通过但解析规则落后且字段语义受影响时标 PARSE_DIFF。
 */
@Service
public class IngestionService {

    private final JdbcTemplate jdbc;

    public IngestionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listPackages(boolean includePayload) {
        String sql = "SELECT package_code, source_system, source_revision, payload_hash, parse_rule_version, "
                + "parse_status, received_at, event_time, supersedes_package_code"
                + (includePayload ? ", payload" : "") + " FROM source_package ORDER BY received_at";
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = baseRow(rs);
            if (includePayload) {
                m.put("payload", Json.map(rs.getObject("payload")));
            }
            return m;
        });
    }

    public Map<String, Object> getPackage(String code) {
        return jdbc.query("SELECT * FROM source_package WHERE package_code=?", rs -> {
            if (!rs.next()) {
                throw new IllegalArgumentException("来源包不存在: " + code);
            }
            Map<String, Object> m = baseRow(rs);
            m.put("payload", Json.map(rs.getObject("payload")));
            return m;
        }, code);
    }

    public List<Map<String, Object>> listParseRules() {
        return jdbc.query("SELECT version, effective_at, description, affected_types FROM parse_rule ORDER BY effective_at",
                (rs, i) -> Map.of(
                        "version", rs.getString("version"),
                        "effectiveAt", rs.getObject("effective_at").toString(),
                        "description", rs.getString("description"),
                        "affectedTypes", rs.getString("affected_types")));
    }

    /** 检验更正版本：取自 QC_LAB 来源包，附接收时间与被替代包号。 */
    public List<Map<String, Object>> listInspectionVersions() {
        return jdbc.query("SELECT source_revision, received_at, supersedes_package_code FROM source_package "
                        + "WHERE source_system='QC_LAB' ORDER BY received_at",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", rs.getString("source_revision"));
                    m.put("receivedAt", rs.getObject("received_at").toString());
                    m.put("supersedesPackageCode", rs.getString("supersedes_package_code"));
                    return m;
                });
    }

    public List<Map<String, Object>> listBatches() {
        return jdbc.query("SELECT batch_code, batch_type, product_code, product_name, unit, quantity, attributes "
                        + "FROM batch_master ORDER BY batch_code",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("batchCode", rs.getString("batch_code"));
                    m.put("batchType", rs.getString("batch_type"));
                    m.put("productCode", rs.getString("product_code"));
                    m.put("productName", rs.getString("product_name"));
                    m.put("unit", rs.getString("unit"));
                    m.put("quantity", rs.getBigDecimal("quantity"));
                    m.put("attributes", Json.map(rs.getObject("attributes")));
                    return m;
                });
    }

    private Map<String, Object> baseRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("packageCode", rs.getString("package_code"));
        m.put("sourceSystem", rs.getString("source_system"));
        m.put("sourceRevision", rs.getString("source_revision"));
        m.put("payloadHash", rs.getString("payload_hash"));
        m.put("parseRuleVersion", rs.getString("parse_rule_version"));
        m.put("parseStatus", rs.getString("parse_status"));
        m.put("receivedAt", rs.getObject("received_at").toString());
        m.put("eventTime", rs.getObject("event_time").toString());
        m.put("supersedesPackageCode", rs.getString("supersedes_package_code"));
        return m;
    }

    /**
     * 解析状态判定：哈希有效（受理时已计算）但解析规则版本落后于当前规则，
     * 且当前规则影响该包事实类型时，标记 PARSE_DIFF。
     */
    public String resolveParseStatus(String packageCode, String currentParseVersion, String currentAffectedTypes) {
        return jdbc.query("SELECT parse_rule_version, parse_status FROM source_package WHERE package_code=?", rs -> {
            if (!rs.next()) {
                return "FAILED";
            }
            String pkgVersion = rs.getString("parse_rule_version");
            String stored = rs.getString("parse_status");
            if ("FAILED".equals(stored)) {
                return "FAILED";
            }
            if (!pkgVersion.equals(currentParseVersion) && stored.equals("PARSE_DIFF")) {
                return "PARSE_DIFF";
            }
            return "PARSED";
        }, packageCode);
    }
}
