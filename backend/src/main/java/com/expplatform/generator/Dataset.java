package com.expplatform.generator;

import com.expplatform.schema.Schema;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory output of the generator: rows per table, ordered by the schema's column order. */
public class Dataset {

    public record ExperimentPlan(String experimentId, int index, ScenarioCatalog.ScenarioSpec scenario,
                                 LocalDateTime analysisAt, LocalDateTime decisionAt,
                                 Map<String, String> variantIds) {}

    public final long seed;
    public final int users;
    public final int experiments;
    public final Map<String, List<Object[]>> tables = new LinkedHashMap<>();
    public final List<ExperimentPlan> plans = new ArrayList<>();
    public int gtCounter = 0;
    public int statusEventCounter = 0;

    public Dataset(long seed, int users, int experiments) {
        this.seed = seed;
        this.users = users;
        this.experiments = experiments;
        for (Schema.Table t : Schema.allTables()) tables.put(t.name(), new ArrayList<>());
    }

    /** Adds a row given as alternating column/value pairs; missing columns become NULL. */
    private static final Map<String, Map<String, Integer>> COLUMN_INDEX = new LinkedHashMap<>();
    static {
        for (Schema.Table t : Schema.allTables()) {
            Map<String, Integer> idx = new LinkedHashMap<>();
            List<String> names = t.columnNames();
            for (int i = 0; i < names.size(); i++) idx.put(names.get(i), i);
            COLUMN_INDEX.put(t.name(), idx);
        }
    }

    public void add(String table, Object... kv) {
        Map<String, Integer> cols = COLUMN_INDEX.get(table);
        Object[] row = new Object[cols.size()];
        for (int i = 0; i < kv.length; i += 2) {
            int idx = cols.getOrDefault((String) kv[i], -1);
            if (idx < 0) throw new IllegalArgumentException("Unknown column " + table + "." + kv[i]);
            row[idx] = kv[i + 1];
        }
        tables.get(table).add(row);
    }

    public String nextGtId() {
        return String.format("GT-2026-%06d", ++gtCounter);
    }

    public void groundTruth(String scope, String issueType, String entityType, String entityId, String experimentId,
                            String expectedValue, String notes) {
        add("ground_truth", "gt_id", nextGtId(), "scope", scope, "issue_type", issueType, "entity_type", entityType,
            "entity_id", entityId, "experiment_id", experimentId, "expected_value", expectedValue, "notes", notes);
    }
}
