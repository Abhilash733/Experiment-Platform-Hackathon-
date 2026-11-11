package com.expplatform.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Canonical text form of values: used identically by CSV/JSON/SQL exports and by checksums. */
public final class Values {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Values() {}

    public static String text(Object v) {
        if (v == null) return "";
        if (v instanceof LocalDateTime t) return t.format(DT);
        if (v instanceof LocalDate d) return d.toString();
        if (v instanceof BigDecimal b) return b.toPlainString();
        if (v instanceof Boolean b) return b ? "true" : "false";
        return v.toString();
    }

    public static String csv(Object v) {
        String s = text(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static String json(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) {
            if (v instanceof BigDecimal b) return b.toPlainString();
            return v.toString();
        }
        String s = text(v);
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    public static String sql(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Boolean b) return b ? "1" : "0";
        if (v instanceof Number) return json(v);
        String s = v instanceof LocalDateTime t ? t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : text(v);
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
