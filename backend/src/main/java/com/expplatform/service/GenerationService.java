package com.expplatform.service;

import com.expplatform.analysis.AnalysisService;
import com.expplatform.generator.Dataset;
import com.expplatform.generator.SyntheticDataGenerator;
import com.expplatform.repository.Db;
import com.expplatform.repository.SchemaInitializer;
import com.expplatform.schema.Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full deterministic pipeline: generate in memory -> reset dataset tables -> bulk load ->
 * run the predeclared analysis at a fixed simulated time -> record decisions -> post-result
 * metric redefinition -> remaining ground-truth labels -> validation.
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private final Db db;
    private final SchemaInitializer schema;
    private final AnalysisService analysis;
    private final LifecycleService lifecycle;
    private final ValidationService validation;
    private final ObjectMapper mapper;

    public GenerationService(Db db, SchemaInitializer schema, AnalysisService analysis, LifecycleService lifecycle,
                             @Lazy ValidationService validation, ObjectMapper mapper) {
        this.db = db;
        this.schema = schema;
        this.analysis = analysis;
        this.lifecycle = lifecycle;
        this.validation = validation;
        this.mapper = mapper;
    }

    public synchronized Map<String, Object> generate(long seed, int users, int experiments, String triggeredBy, boolean validate) {
        long t0 = System.currentTimeMillis();
        String genRunId = db.nextId("generation_run", "gen_run_id", "GEN-2026-", 6);
        db.update("INSERT INTO generation_run (gen_run_id, seed, users, experiments, started_at, status, triggered_by) VALUES (?,?,?,?,?,?,?)",
            genRunId, seed, users, experiments, LocalDateTime.now().withNano(0), "RUNNING", triggeredBy);
        try {
            Dataset ds = new SyntheticDataGenerator().generate(seed, users, experiments);
            log.info("Generated dataset in memory in {} ms", System.currentTimeMillis() - t0);

            schema.truncateDataset();
            for (Schema.Table t : Schema.allTables()) {
                db.batchInsert(t.name(), t.columnNames(), ds.tables.get(t.name()));
            }
            log.info("Loaded dataset in {} ms", System.currentTimeMillis() - t0);

            // Analysis + decisions at fixed simulated timestamps (deterministic)
            List<String[]> metricChangedRuns = new ArrayList<>();
            LocalDateTime latestMetricChangeAnalysis = null;
            for (Dataset.ExperimentPlan p : ds.plans) {
                String analyst = "analyst." + p.scenario().team().toLowerCase();
                Map<String, Object> run = analysis.analyze(p.experimentId(), p.analysisAt(), analyst);
                recordDecision(ds, p, run);
                if ("METRIC_CHANGED".equals(p.scenario().code())) {
                    metricChangedRuns.add(new String[]{(String) run.get("run_id"), p.experimentId()});
                    if (latestMetricChangeAnalysis == null || p.analysisAt().isAfter(latestMetricChangeAnalysis)) latestMetricChangeAnalysis = p.analysisAt();
                }
            }

            // Post-result metric redefinition (new version row; v1 is never modified)
            if (latestMetricChangeAnalysis != null) {
                LocalDateTime eff = latestMetricChangeAnalysis.plusDays(10).withHour(9);
                db.update("INSERT INTO metric_definition (metric_def_id, metric_code, version, name, description, metric_type, source_entity, event_type, value_field, window_days, unit, desired_direction, effective_from, created_by, change_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    SyntheticDataGenerator.metricDefId("TRIAL_ACTIVATION", 2), "TRIAL_ACTIVATION", 2, "Trial activation (3d)",
                    "Share of analysed users with >= 1 TRIAL_ACTIVATION event in [assigned_at, assigned_at + 3 days).",
                    "PROPORTION", "CONVERSION_EVENT", "TRIAL_ACTIVATION", null, 3, "ratio", "INCREASE", eff,
                    "metric.owner.product", "Product redefined activation as happening within 72h of assignment (after results were published)");
                for (String[] r : metricChangedRuns) {
                    insertGroundTruth(ds, "ROW", "METRIC_DEFINITION_CHANGED_AFTER_RESULT", "ANALYSIS_RUN", r[0], r[1],
                        SyntheticDataGenerator.metricDefId("TRIAL_ACTIVATION", 2),
                        "Primary metric got a new version effective " + eff + " after this result was computed");
                }
            }

            Map<String, Long> counts = rowCounts();
            long ms = System.currentTimeMillis() - t0;
            db.update("UPDATE generation_run SET finished_at = ?, duration_ms = ?, status = ?, row_counts = ? WHERE gen_run_id = ?",
                LocalDateTime.now().withNano(0), ms, "SUCCEEDED", mapper.writeValueAsString(counts), genRunId);
            log.info("Generation {} finished in {} ms: {}", genRunId, ms, counts);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("gen_run_id", genRunId);
            out.put("seed", seed);
            out.put("users", users);
            out.put("experiments", experiments);
            out.put("duration_ms", ms);
            out.put("row_counts", counts);
            if (validate) {
                Map<String, Object> v = validation.run(genRunId);
                out.put("validation", Map.of("validation_run_id", v.get("validation_run_id"), "passed", v.get("passed"),
                    "failed", v.get("failed"), "warnings", v.get("warnings")));
            }
            return out;
        } catch (RuntimeException e) {
            db.update("UPDATE generation_run SET finished_at = ?, status = ? WHERE gen_run_id = ?", LocalDateTime.now().withNano(0), "FAILED", genRunId);
            throw e;
        } catch (Exception e) {
            db.update("UPDATE generation_run SET finished_at = ?, status = ? WHERE gen_run_id = ?", LocalDateTime.now().withNano(0), "FAILED", genRunId);
            throw new IllegalStateException(e);
        }
    }

    private void recordDecision(Dataset ds, Dataset.ExperimentPlan p, Map<String, Object> run) {
        String rec = (String) run.get("recommendation");
        String decision, rationale, chosen = null;
        boolean srmAck = false, grAck = false;
        String by = "decision.board." + p.scenario().team().toLowerCase();
        switch (rec) {
            case "SHIP" -> { decision = "SHIP"; chosen = (String) run.get("recommended_variant_id"); rationale = "Primary metric significantly improved; blocking guardrails within tolerance."; }
            case "DO_NOT_SHIP" -> { decision = "DO_NOT_SHIP"; rationale = "Primary metric significantly worse than control."; }
            case "BLOCKED_GUARDRAIL" -> { decision = "DO_NOT_SHIP"; grAck = true; rationale = "Blocking guardrail failed; treatment harms customer experience."; }
            case "INVALID_SRM" -> {
                if ("SHIP_IGNORING_SRM".equals(p.scenario().decisionOverride())) {
                    decision = "SHIP";
                    chosen = p.variantIds().get("B");
                    rationale = "Treatment lift looks strong; shipping now.";
                } else {
                    decision = "ITERATE"; srmAck = true;
                    rationale = "Sample ratio mismatch detected; fix assignment logging and rerun the experiment.";
                }
            }
            default -> { decision = "ITERATE"; rationale = "No significant effect at the planned power; iterate on the treatment."; }
        }
        String id = db.nextId("experiment_decision", "decision_id", "DEC-2026-", 6);
        db.update("INSERT INTO experiment_decision (decision_id, experiment_id, run_id, decision, chosen_variant_id, decided_by, decided_at, srm_acknowledged, guardrails_acknowledged, rationale) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, p.experimentId(), run.get("run_id"), decision, chosen, by, p.decisionAt(), srmAck, grAck, rationale);
        lifecycle.transition(p.experimentId(), "DECIDED", p.decisionAt(), by, "Decision " + id + ": " + decision);
        if ("SHIP_IGNORING_SRM".equals(p.scenario().decisionOverride())) {
            insertGroundTruth(ds, "ROW", "SRM_IGNORED", "EXPERIMENT_DECISION", id, p.experimentId(), "ITERATE",
                "Decision SHIP recorded while the run had a failing SRM check and srm_acknowledged = false");
        }
    }

    private void insertGroundTruth(Dataset ds, String scope, String type, String entityType, String entityId, String expId,
                                   String expected, String notes) {
        db.update("INSERT INTO ground_truth (gt_id, scope, issue_type, entity_type, entity_id, experiment_id, expected_value, notes) VALUES (?,?,?,?,?,?,?,?)",
            ds.nextGtId(), scope, type, entityType, entityId, expId, expected, notes);
    }

    public Map<String, Long> rowCounts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Schema.Table t : Schema.allTables()) m.put(t.name(), db.count("SELECT COUNT(*) FROM " + t.name()));
        return m;
    }
}
