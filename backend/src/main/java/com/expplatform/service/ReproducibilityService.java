package com.expplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Regenerates twice with the same seed and compares row counts and SHA-256 checksums per table. */
@Service
public class ReproducibilityService {

    private final GenerationService generation;
    private final ExportService export;
    private final ValidationService validation;
    private final ObjectMapper mapper;

    public ReproducibilityService(GenerationService generation, ExportService export, ValidationService validation, ObjectMapper mapper) {
        this.generation = generation;
        this.export = export;
        this.validation = validation;
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public synchronized Map<String, Object> check(long seed, int users, int experiments) throws Exception {
        Map<String, Object> g1 = generation.generate(seed, users, experiments, "reproducibility.check#1", false);
        Map<String, Map<String, Object>> c1 = export.tableChecksums();
        Map<String, Object> g2 = generation.generate(seed, users, experiments, "reproducibility.check#2", false);
        Map<String, Map<String, Object>> c2 = export.tableChecksums();
        validation.run((String) g2.get("gen_run_id"));

        List<Map<String, Object>> tables = new ArrayList<>();
        boolean all = true;
        for (String t : c1.keySet()) {
            Map<String, Object> a = c1.get(t), b = c2.get(t);
            boolean same = a.equals(b);
            all &= same;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t);
            m.put("rows_run1", a.get("rows"));
            m.put("rows_run2", b.get("rows"));
            m.put("sha256_run1", a.get("sha256"));
            m.put("sha256_run2", b.get("sha256"));
            m.put("identical", same);
            tables.add(m);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checked_at", Instant.now().toString());
        report.put("seed", seed);
        report.put("users", users);
        report.put("experiments", experiments);
        report.put("run1", g1.get("gen_run_id"));
        report.put("run2", g2.get("gen_run_id"));
        report.put("reproducible", all);
        report.put("tables", tables);
        Files.createDirectories(export.root());
        mapper.writeValue(export.root().resolve("reproducibility_report.json").toFile(), report);
        report.put("report_file", export.root().resolve("reproducibility_report.json").toString());
        return report;
    }
}
