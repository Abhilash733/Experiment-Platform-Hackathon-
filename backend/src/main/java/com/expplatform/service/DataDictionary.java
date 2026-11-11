package com.expplatform.service;

import com.expplatform.schema.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders the data dictionary from the schema registry. */
public final class DataDictionary {

    private DataDictionary() {}

    public static List<Map<String, Object>> json() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Schema.Table t : Schema.allTables()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t.name());
            m.put("kind", t.kind());
            m.put("description", t.description());
            m.put("primary_key", t.primaryKey());
            m.put("cardinality", t.cardinality());
            m.put("lifecycle", t.lifecycle());
            m.put("composite_unique", t.compositeUniques().stream().map(u -> String.join(", ", u)).toList());
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Schema.Column c : t.columns()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", c.name());
                cm.put("type", c.sqlType());
                cm.put("required", c.required());
                cm.put("allowed_values", c.allowed());
                cm.put("unit", c.unit());
                cm.put("unique", c.unique());
                cm.put("pattern", c.pattern());
                cm.put("foreign_key", c.fkTable() == null ? null : c.fkTable() + "." + c.fkColumn());
                cm.put("validation_rule", c.rule());
                cm.put("description", c.description());
                cols.add(cm);
            }
            m.put("columns", cols);
            out.add(m);
        }
        return out;
    }

    public static String markdown() {
        StringBuilder sb = new StringBuilder("# Data Dictionary\n\n");
        sb.append("Generated from `backend/src/main/java/com/expplatform/schema/Schema.java` (the same registry creates the DDL and drives validation).\n\n");
        sb.append("Timestamps are UTC `DATETIME`. IDs are immutable and zero-padded.\n\n");
        sb.append("| Table | Kind | Primary key | Cardinality |\n|---|---|---|---|\n");
        for (Schema.Table t : Schema.allTables()) {
            sb.append("| [").append(t.name()).append("](#").append(t.name()).append(") | ").append(t.kind()).append(" | `")
                .append(t.primaryKey()).append("` | ").append(t.cardinality()).append(" |\n");
        }
        for (Schema.Table t : Schema.allTables()) {
            sb.append("\n## ").append(t.name()).append("\n\n").append(t.description()).append("\n\n");
            sb.append("- **Kind:** ").append(t.kind()).append("\n");
            sb.append("- **Primary key:** `").append(t.primaryKey()).append("`\n");
            sb.append("- **Cardinality:** ").append(t.cardinality()).append("\n");
            sb.append("- **Lifecycle / effective dates:** ").append(t.lifecycle()).append("\n");
            if (!t.compositeUniques().isEmpty()) {
                sb.append("- **Composite unique:** ");
                t.compositeUniques().forEach(u -> sb.append("`(").append(String.join(", ", u)).append(")` "));
                sb.append("\n");
            }
            sb.append("\n| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |\n|---|---|---|---|---|---|---|---|---|\n");
            for (Schema.Column c : t.columns()) {
                String rule = c.pattern() != null ? "`" + c.pattern() + "`" + (c.rule() != null ? "; " + c.rule() : "") : (c.rule() == null ? "" : c.rule());
                sb.append("| `").append(c.name()).append("` | ").append(c.sqlType()).append(" | ").append(c.required() ? "yes" : "no")
                    .append(" | ").append(String.join(", ", c.allowed())).append(" | ").append(c.unit() == null ? "" : c.unit())
                    .append(" | ").append(c.unique() ? "yes" : "").append(" | ").append(rule.replace("|", "\\|"))
                    .append(" | ").append(c.fkTable() == null ? "" : c.fkTable() + "." + c.fkColumn())
                    .append(" | ").append(c.description()).append(" |\n");
            }
        }
        return sb.toString();
    }
}
