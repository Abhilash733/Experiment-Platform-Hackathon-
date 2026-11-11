package com.expplatform.api;

import com.expplatform.repository.Db;
import com.expplatform.schema.Schema;
import com.expplatform.service.DataDictionary;
import com.expplatform.service.ExperimentService;
import com.expplatform.service.ExportService;
import com.expplatform.service.GenerationService;
import com.expplatform.service.ReproducibilityService;
import com.expplatform.service.ValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generator, validation, ground-truth evaluation, export, reproducibility, dictionary and data browser. */
@RestController
@RequestMapping("/api")
public class PlatformController {

    private final Db db;
    private final GenerationService generation;
    private final ValidationService validation;
    private final ExportService export;
    private final ReproducibilityService repro;
    private final ExperimentService experiments;
    private final long defaultSeed;
    private final int defaultUsers;
    private final int defaultExperiments;

    public PlatformController(Db db, GenerationService generation, ValidationService validation, ExportService export,
                              ReproducibilityService repro, ExperimentService experiments,
                              @Value("${app.seed}") long seed, @Value("${app.users}") int users, @Value("${app.experiments}") int exps) {
        this.db = db;
        this.generation = generation;
        this.validation = validation;
        this.export = export;
        this.repro = repro;
        this.experiments = experiments;
        this.defaultSeed = seed;
        this.defaultUsers = users;
        this.defaultExperiments = exps;
    }

    public record GenerateRequest(Long seed, Integer users, Integer experiments) {}

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("row_counts", generation.rowCounts());
        out.put("latest_generation", db.row("SELECT * FROM generation_run ORDER BY gen_run_id DESC LIMIT 1"));
        Map<String, Object> v = validation.latest();
        Map<String, Object> vs = new LinkedHashMap<>();
        for (String k : List.of("validation_run_id", "run_at", "passed", "failed", "warnings", "evaluation")) vs.put(k, v.get(k));
        out.put("latest_validation", vs);
        out.put("issue_counts", db.rows("SELECT issue_type, COUNT(*) n FROM detected_issue WHERE validation_run_id = (SELECT MAX(validation_run_id) FROM validation_run) GROUP BY issue_type ORDER BY n DESC"));
        out.put("experiments", experiments.list());
        out.put("defaults", Map.of("seed", defaultSeed, "users", defaultUsers, "experiments", defaultExperiments));
        return out;
    }

    // ---------------------------------------------------------------- generator
    @PostMapping("/generator/run")
    public Map<String, Object> generate(@RequestBody(required = false) GenerateRequest req) {
        long seed = req == null || req.seed() == null ? defaultSeed : req.seed();
        int users = req == null || req.users() == null ? defaultUsers : req.users();
        int exps = req == null || req.experiments() == null ? defaultExperiments : req.experiments();
        return generation.generate(seed, users, exps, "ui.sysadmin", true);
    }

    @GetMapping("/generator/runs")
    public List<Map<String, Object>> generationRuns() {
        return db.rows("SELECT * FROM generation_run ORDER BY gen_run_id DESC LIMIT 50");
    }

    // ---------------------------------------------------------------- validation & ground truth
    @PostMapping("/validation/run")
    public Map<String, Object> validate() {
        Map<String, Object> g = db.row("SELECT gen_run_id FROM generation_run WHERE status = 'SUCCEEDED' ORDER BY gen_run_id DESC LIMIT 1");
        return validation.run(g == null ? null : (String) g.get("gen_run_id"));
    }

    @GetMapping("/validation/latest")
    public Map<String, Object> latestValidation() {
        return validation.latest();
    }

    @GetMapping("/validation/issues")
    public List<Map<String, Object>> issues(@RequestParam(required = false) String type, @RequestParam(required = false) String experimentId,
                                            @RequestParam(defaultValue = "200") int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM detected_issue WHERE validation_run_id = (SELECT MAX(validation_run_id) FROM validation_run)");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) { sql.append(" AND issue_type = ?"); args.add(type); }
        if (experimentId != null && !experimentId.isBlank()) { sql.append(" AND experiment_id = ?"); args.add(experimentId); }
        sql.append(" ORDER BY issue_id LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 2000));
        return db.rows(sql.toString(), args.toArray());
    }

    @GetMapping("/ground-truth/summary")
    public List<Map<String, Object>> groundTruthSummary() {
        return db.rows("SELECT issue_type, scope, entity_type, COUNT(*) n FROM ground_truth GROUP BY issue_type, scope, entity_type ORDER BY scope, issue_type");
    }

    // ---------------------------------------------------------------- export & reproducibility
    @PostMapping("/export")
    public Map<String, Object> export() throws Exception {
        return export.export();
    }

    @GetMapping("/export/manifest")
    public Map<String, Object> manifest() throws Exception {
        return export.latestManifest();
    }

    @PostMapping("/reproducibility/check")
    public Map<String, Object> reproducibility(@RequestBody(required = false) GenerateRequest req) throws Exception {
        long seed = req == null || req.seed() == null ? defaultSeed : req.seed();
        int users = req == null || req.users() == null ? defaultUsers : req.users();
        int exps = req == null || req.experiments() == null ? defaultExperiments : req.experiments();
        return repro.check(seed, users, exps);
    }

    // ---------------------------------------------------------------- dictionary & browser
    @GetMapping("/dictionary")
    public List<Map<String, Object>> dictionary() {
        return DataDictionary.json();
    }

    @GetMapping("/data/{table}")
    public Map<String, Object> browse(@PathVariable String table, @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset, @RequestParam(required = false) String experimentId) {
        Schema.Table t = Schema.table(table);
        if (t == null) throw ApiException.notFound("Table " + table);
        boolean filter = experimentId != null && !experimentId.isBlank() && t.column("experiment_id") != null;
        String where = filter ? " WHERE experiment_id = ?" : "";
        Object[] args = filter ? new Object[]{experimentId} : new Object[]{};
        long total = db.count("SELECT COUNT(*) FROM " + t.name() + where, args);
        List<Object> a = new ArrayList<>(List.of(args));
        a.add(Math.min(Math.max(limit, 1), 500));
        a.add(Math.max(offset, 0));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", t.name());
        out.put("columns", t.columnNames());
        out.put("total", total);
        out.put("rows", db.rows("SELECT * FROM " + t.name() + where + " ORDER BY " + t.primaryKey() + " LIMIT ? OFFSET ?", a.toArray()));
        return out;
    }
}
