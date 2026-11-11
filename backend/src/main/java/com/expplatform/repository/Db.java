package com.expplatform.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Thin JdbcTemplate wrapper: normalized row maps, counters for deterministic IDs and batch inserts. */
@Component
public class Db {

    public final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static Object normalize(Object v) {
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return v;
    }

    private static Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i), normalize(rs.getObject(i)));
        return m;
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> mapRow(rs), args);
    }

    public Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> r = rows(sql, args);
        return r.isEmpty() ? null : r.get(0);
    }

    public long count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? 0 : n.longValue();
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /** Streams rows one by one (used by exports and checksums to avoid loading 100k rows at once). */
    public void stream(String sql, Consumer<Object[]> consumer) {
        jdbc.query(con -> {
            var ps = con.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(Integer.MIN_VALUE); // MySQL streaming
            return ps;
        }, rs -> {
            int n = rs.getMetaData().getColumnCount();
            Object[] row = new Object[n];
            for (int i = 1; i <= n; i++) row[i - 1] = normalize(rs.getObject(i));
            consumer.accept(row);
        });
    }

    public void batchInsert(String table, List<String> columns, List<Object[]> rows) {
        if (rows.isEmpty()) return;
        String sql = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES ("
            + String.join(",", columns.stream().map(c -> "?").toList()) + ")";
        int chunk = 5000;
        for (int i = 0; i < rows.size(); i += chunk) {
            List<Object[]> part = new ArrayList<>(rows.subList(i, Math.min(rows.size(), i + chunk)));
            jdbc.batchUpdate(sql, part);
        }
    }

    /** Next zero-padded id for prefixes like RUN-2026- (counter continues from the max existing id). */
    public synchronized String nextId(String table, String pkColumn, String prefix, int width) {
        String max = jdbc.queryForObject("SELECT MAX(" + pkColumn + ") FROM " + table + " WHERE " + pkColumn + " LIKE ?",
            String.class, prefix + "%");
        long next = 1;
        if (max != null) next = Long.parseLong(max.substring(prefix.length())) + 1;
        return prefix + String.format("%0" + width + "d", next);
    }
}
