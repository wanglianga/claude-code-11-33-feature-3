package com.trace.recall.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;

import java.util.List;
import java.util.Map;

/**
 * JSONB 序列化统一入口：写入 PG jsonb，读出时统一转 Map/List。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static PGobject jsonb(Object value) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(MAPPER.writeValueAsString(value));
            return pg;
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 序列化失败", e);
        }
    }

    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object dbValue) {
        if (dbValue == null) {
            return Map.of();
        }
        try {
            String text = dbValue instanceof PGobject pg ? pg.getValue() : String.valueOf(dbValue);
            if (text == null || text.isBlank()) {
                return Map.of();
            }
            return MAPPER.readValue(text, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 反序列化(Map)失败: " + dbValue, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object dbValue) {
        if (dbValue == null) {
            return List.of();
        }
        try {
            String text = dbValue instanceof PGobject pg ? pg.getValue() : String.valueOf(dbValue);
            if (text == null || text.isBlank()) {
                return List.of();
            }
            return MAPPER.readValue(text, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 反序列化(List)失败: " + dbValue, e);
        }
    }
}
