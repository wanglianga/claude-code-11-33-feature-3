package com.trace.recall.identity;

import com.trace.recall.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次身份解析：供应商批号/生产批号/箱码 → 内部批次。
 * 强制规则：同一内部批号被两个供应商批号观测且均无装箱凭证时，保留双候选、不合并。
 */
@Service
public class IdentityResolutionService {

    private final JdbcTemplate jdbc;

    public IdentityResolutionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void resolve(long runId, List<Long> snapshotPackageIds) {
        if (snapshotPackageIds.isEmpty()) {
            return;
        }
        List<Map<String, Object>> supplierLots = new ArrayList<>();
        List<Map<String, Object>> productionLots = new ArrayList<>();
        for (Long pkgId : snapshotPackageIds) {
            List<Map<String, Object>> facts = jdbc.query(
                    "SELECT fact_type, payload FROM raw_fact WHERE package_id=? AND fact_type='MASTER_DATA'",
                    (rs, i) -> Json.map(rs.getObject("payload")), pkgId);
            for (Map<String, Object> mdm : facts) {
                for (Object o : (List<?>) mdm.getOrDefault("supplierLots", List.of())) {
                    supplierLots.add(castMap(o));
                }
                for (Object o : (List<?>) mdm.getOrDefault("productionLots", List.of())) {
                    productionLots.add(castMap(o));
                }
            }
        }

        // 统计每个内部批号对应的无凭证供应商批号数量
        Map<String, Integer> unverifiedPerBatch = new HashMap<>();
        for (Map<String, Object> lot : supplierLots) {
            String internal = str(lot.get("internalBatch"));
            boolean voucher = Boolean.TRUE.equals(lot.get("packingVoucher"));
            if (internal != null && !voucher) {
                unverifiedPerBatch.merge(internal, 1, Integer::sum);
            }
        }

        for (Map<String, Object> lot : supplierLots) {
            String lotCode = str(lot.get("lot"));
            String internal = str(lot.get("internalBatch"));
            boolean voucher = Boolean.TRUE.equals(lot.get("packingVoucher"));
            boolean ambiguous = internal != null && unverifiedPerBatch.getOrDefault(internal, 0) >= 2;
            String matchStatus = internal == null ? "UNMATCHED" : ambiguous ? "AMBIGUOUS" : "MATCHED";
            long recordId = insertRecord(runId, "SUPPLIER_LOT", lotCode, internal, matchStatus);
            if (internal != null) {
                // 歧义时为同一内部批号保留候选，证据中注明缺失装箱凭证，不生成任何合并边
                insertCandidate(recordId, internal, ambiguous ? "MEDIUM" : voucher ? "HIGH" : "MEDIUM",
                        ambiguous, List.of(Map.of(
                                "supplierLot", lotCode, "packingVoucher", voucher,
                                "note", ambiguous ? "同一内部批号存在多个无凭证供应商批号，保留候选不合并" : "供应商目录观测")));
            }
        }

        for (Map<String, Object> lot : productionLots) {
            String lotCode = str(lot.get("lot"));
            String internal = str(lot.get("internalBatch"));
            String matchStatus = internal == null ? "UNMATCHED" : "MATCHED";
            long recordId = insertRecord(runId, "PRODUCTION_LOT", lotCode, internal, matchStatus);
            if (internal != null) {
                insertCandidate(recordId, internal, "HIGH", false,
                        List.of(Map.of("productionLot", lotCode, "note", "生产批号唯一映射")));
            }
        }
    }

    private long insertRecord(long runId, String codeType, String codeValue, String observed, String matchStatus) {
        jdbc.update("INSERT INTO identity_record(run_id, code_type, code_value, observed_batch_code, match_status) "
                + "VALUES (?,?,?,?,?)", runId, codeType, codeValue, observed, matchStatus);
        return jdbc.queryForObject(
                "SELECT id FROM identity_record WHERE run_id=? AND code_type=? AND code_value=?",
                Long.class, runId, codeType, codeValue);
    }

    private void insertCandidate(long recordId, String batchCode, String confidence, boolean ambiguous, Object evidence) {
        jdbc.update("INSERT INTO identity_candidate(identity_record_id, candidate_batch_code, confidence, ambiguous, evidence) "
                + "VALUES (?,?,?,?,?)", recordId, batchCode, confidence, ambiguous, Json.jsonb(evidence));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
