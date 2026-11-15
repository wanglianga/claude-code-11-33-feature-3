package com.trace.recall.common;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时间解析：同时兼容 ISO-8601 带偏移量字符串与 PostgreSQL JDBC 返回的 "yyyy-MM-dd HH:mm:ss.S" 文本。
 */
public final class TimeUtil {

    private static final DateTimeFormatter JDBC_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");

    private TimeUtil() {
    }

    public static OffsetDateTime parseFlexible(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        if (t.contains("T")) {
            return OffsetDateTime.parse(t);
        }
        // JDBC TIMESTAMPTZ 文本（无时区后缀），按 UTC+8（应用时区）解释
        String normalized = t.length() > 19 ? t.substring(0, 19) : t;
        LocalDateTime ldt = LocalDateTime.parse(normalized,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return ldt.atOffset(ZoneOffset.ofHours(8));
    }
}
