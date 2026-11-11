package com.expplatform.service;

import com.expplatform.repository.Db;
import com.expplatform.repository.Values;
import com.expplatform.schema.Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Exports the dataset (CSV/JSON/SQL), ground truth (separately), dictionary, validation summary, README and manifest. */
@Service
public class ExportService {

    private final Db db;
    private final ValidationService validation;
    private final ObjectMapper mapper;
    private final Path exportRoot;

    public ExportService(Db db, ValidationService validation, ObjectMapper mapper, @Value("${app.export-dir:../exports}") String exportDir) {
        this.db = db;
        this.validation = validation;
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.exportRoot = Path.of(exportDir).toAbsolutePath().normalize();
    }

    public Path root() { return exportRoot; }

    // ------------------------------------------------------------------ checksums (canonical CSV, PK order)
    public Map<String, Map<String, Object>> tableChecksums() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Schema.Table t : Schema.allTables()) {
            MessageDigest md = sha();
            long[] n = {0};
            md.update((String.join(",", t.columnNames()) + "\n").getBytes(StandardCharsets.UTF_8));
            db.stream(select(t), row -> { md.update(csvLine(row).getBytes(StandardCharsets.UTF_8)); n[0]++; });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", n[0]);
            m.put("sha256", HexFormat.of().formatHex(md.digest()));
            out.put(t.name(), m);
        }
        return out;
    }

    private static String select(Schema.Table t) {
        return "SELECT " + String.join(",", t.columnNames()) + " FROM " + t.name() + " ORDER BY " + t.primaryKey();
    }

    private static String csvLine(Object[] row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) { if (i > 0) sb.append(','); sb.append(Values.csv(row[i])); }
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------------ export
    public synchronized Map<String, Object> export() throws IOException {
        Map<String, Object> gen = db.row("SELECT * FROM generation_run WHERE status = 'SUCCEEDED' ORDER BY gen_run_id DESC LIMIT 1");
        long seed = gen == null ? 0 : ((Number) gen.get("seed")).longValue();
        int users = gen == null ? 0 : ((Number) gen.get("users")).intValue();
        int exps = gen == null ? 0 : ((Number) gen.get("experiments")).intValue();
        Path dir = exportRoot.resolve("dataset_seed-" + seed + "_users-" + users + "_experiments-" + exps);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(p);
            }
        }
        Path csv = Files.createDirectories(dir.resolve("data/csv"));
        Path json = Files.createDirectories(dir.resolve("data/json"));
        Path sql = Files.createDirectories(dir.resolve("data/sql"));
        Path gt = Files.createDirectories(dir.resolve("ground_truth"));
        Path docs = Files.createDirectories(dir.resolve("docs"));

        List<Map<String, Object>> tables = new ArrayList<>();
        try (BufferedWriter schemaW = Files.newBufferedWriter(sql.resolve("schema.sql"));
             BufferedWriter seedW = Files.newBufferedWriter(sql.resolve("seed.sql"))) {
            schemaW.write("-- Generated from the schema registry (com.expplatform.schema.Schema)\nCREATE DATABASE IF NOT EXISTS experiment_platform;\nUSE experiment_platform;\n\n");
            seedW.write("-- Seed data. Load schema.sql first.\nUSE experiment_platform;\nSET FOREIGN_KEY_CHECKS = 0;\n\n");
            for (Schema.Table t : Schema.allTables()) {
                schemaW.write(Schema.ddl(t) + ";\n\n");
                boolean isGt = t == Schema.GROUND_TRUTH;
                Path csvFile = (isGt ? gt : csv).resolve(t.name() + ".csv");
                Path jsonFile = (isGt ? gt : json).resolve(t.name() + ".json");
                long rows = writeTable(t, csvFile, jsonFile, isGt ? null : seedW);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", t.name());
                m.put("kind", t.kind());
                m.put("rows", rows);
                m.put("csv", dir.relativize(csvFile).toString().replace('\\', '/'));
                m.put("json", dir.relativize(jsonFile).toString().replace('\\', '/'));
                tables.add(m);
            }
            seedW.write("SET FOREIGN_KEY_CHECKS = 1;\n");
        }
        // ground-truth seed kept separate from the input data
        try (BufferedWriter w = Files.newBufferedWriter(gt.resolve("ground_truth_seed.sql"))) {
            w.write("USE experiment_platform;\n");
            writeInserts(Schema.GROUND_TRUTH, w);
        }

        Files.writeString(docs.resolve("data_dictionary.md"), DataDictionary.markdown());
        mapper.writeValue(docs.resolve("data_dictionary.json").toFile(), DataDictionary.json());
        mapper.writeValue(dir.resolve("validation_summary.json").toFile(), validation.latest());
        Files.writeString(dir.resolve("README.md"), readme(seed, users, exps));

        // manifest last: checksums of every file
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", dir.relativize(p).toString().replace('\\', '/'));
                f.put("bytes", Files.size(p));
                f.put("sha256", fileSha(p));
                files.add(f);
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("dataset", "Experimentation platform synthetic dataset");
        manifest.put("generator", "com.expplatform.generator.SyntheticDataGenerator v1.0.0");
        manifest.put("seed", seed);
        manifest.put("users", users);
        manifest.put("experiments", exps);
        manifest.put("generator_command", "cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=\"--app.seed=" + seed
            + " --app.users=" + users + " --app.experiments=" + exps + " --generate --export --exit\"");
        manifest.put("source_generation_run", gen == null ? null : gen.get("gen_run_id"));
        manifest.put("exported_at", Instant.now().toString());
        manifest.put("provenance", Map.of(
            "external_data_used", false,
            "description", "100% synthetic. All records are produced by the seeded generator from documented rules (ScenarioCatalog + SyntheticDataGenerator). No personal, confidential or third-party data was collected or used.",
            "license", "Synthetic data, free to use and redistribute",
            "reference_material", "Statistical methods (two-proportion z-test, Welch test, Pearson chi-square SRM test, Bonferroni) are standard textbook methods; no dataset was copied."));
        manifest.put("tables", tables);
        manifest.put("table_checksums", tableChecksums());
        manifest.put("files", files);
        mapper.writeValue(dir.resolve("manifest.json").toFile(), manifest);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("export_dir", dir.toString());
        out.put("tables", tables);
        out.put("files", files.size());
        return out;
    }

    public Map<String, Object> latestManifest() throws IOException {
        if (!Files.exists(exportRoot)) return Map.of("message", "Nothing exported yet");
        try (Stream<Path> s = Files.list(exportRoot)) {
            Path latest = s.filter(p -> Files.exists(p.resolve("manifest.json")))
                .max((a, b) -> Long.compare(a.resolve("manifest.json").toFile().lastModified(), b.resolve("manifest.json").toFile().lastModified()))
                .orElse(null);
            if (latest == null) return Map.of("message", "Nothing exported yet");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(latest.resolve("manifest.json").toFile(), Map.class);
            m.put("export_dir", latest.toString());
            return m;
        }
    }

    private long writeTable(Schema.Table t, Path csvFile, Path jsonFile, BufferedWriter seed) throws IOException {
        long[] n = {0};
        List<String> cols = t.columnNames();
        try (BufferedWriter c = Files.newBufferedWriter(csvFile); BufferedWriter j = Files.newBufferedWriter(jsonFile)) {
            c.write(String.join(",", cols) + "\n");
            j.write("[\n");
            IOException[] err = {null};
            db.stream(select(t), row -> {
                try {
                    c.write(csvLine(row));
                    StringBuilder sb = new StringBuilder(n[0] == 0 ? "  {" : ",\n  {");
                    for (int i = 0; i < row.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append('"').append(cols.get(i)).append("\": ").append(Values.json(row[i]));
                    }
                    j.write(sb.append('}').toString());
                    n[0]++;
                } catch (IOException e) { err[0] = e; }
            });
            if (err[0] != null) throw err[0];
            j.write("\n]\n");
        }
        if (seed != null) writeInserts(t, seed);
        return n[0];
    }

    private void writeInserts(Schema.Table t, BufferedWriter w) throws IOException {
        String head = "INSERT INTO " + t.name() + " (" + String.join(",", t.columnNames()) + ") VALUES\n";
        List<String> buf = new ArrayList<>();
        IOException[] err = {null};
        db.stream(select(t), row -> {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < row.length; i++) { if (i > 0) sb.append(','); sb.append(Values.sql(row[i])); }
            buf.add(sb.append(')').toString());
            if (buf.size() == 500) {
                try { w.write(head + String.join(",\n", buf) + ";\n"); } catch (IOException e) { err[0] = e; }
                buf.clear();
            }
        });
        if (err[0] != null) throw err[0];
        if (!buf.isEmpty()) w.write(head + String.join(",\n", buf) + ";\n");
        w.write("\n");
    }

    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String fileSha(Path p) throws IOException {
        MessageDigest md = sha();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = new byte[65536];
            int r;
            while ((r = in.read(b)) > 0) md.update(b, 0, r);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String readme(long seed, int users, int exps) {
        return """
            # Experimentation Platform - Synthetic Dataset

            Seed `%d`, %d users, %d experiments. 100%% synthetic (no external or personal data).

            ## Layout
            | Path | Content |
            |---|---|
            | `data/csv/*.csv` | One CSV per table, ordered by primary key, header row, ISO-8601 UTC timestamps |
            | `data/json/*.json` | Same rows as JSON arrays |
            | `data/sql/schema.sql` | MySQL DDL with primary keys, foreign keys, unique keys |
            | `data/sql/seed.sql` | INSERT statements for all input tables (load after schema.sql) |
            | `ground_truth/` | Labels for anomalies and expected decisions. **Never join these into model/analysis features.** |
            | `docs/data_dictionary.md` | Types, required flags, allowed values, units, uniqueness, validation rules, cardinality |
            | `validation_summary.json` | Integrity checks, detector precision/recall, recommendation accuracy, CI coverage |
            | `manifest.json` | Generator command, seed, provenance, row counts and SHA-256 of every table/file |

            ## Regenerate
            ```
            cd backend
            mvn spring-boot:run -Dspring-boot.run.arguments="--app.seed=%d --app.users=%d --app.experiments=%d --generate --export --exit"
            ```
            The same seed and scale always produce identical table checksums (see `manifest.json` -> `table_checksums`).

            ## Load into MySQL
            ```
            mysql -u root -p < data/sql/schema.sql
            mysql -u root -p < data/sql/seed.sql
            mysql -u root -p < ground_truth/ground_truth_seed.sql
            ```
            """.formatted(seed, users, exps, seed, users, exps);
    }
}
