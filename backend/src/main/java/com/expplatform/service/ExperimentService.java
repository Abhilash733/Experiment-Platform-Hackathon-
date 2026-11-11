package com.expplatform.service;

import com.expplatform.analysis.AnalysisService;
import com.expplatform.analysis.Stats;
import com.expplatform.api.ApiException;
import com.expplatform.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExperimentService {

    public record VariantReq(String name, String description, Double allocationPct) {}
    public record GuardrailReq(String metricDefId, Double maxRelativeDegradation, String severity) {}
    public record CreateExperimentRequest(String name, String hypothesis, String ownerTeam, String requestedBy,
                                          String primaryMetricDefId, LocalDateTime startAt, LocalDateTime endAt,
                                          Double baselineRate, Double mdeRelative, Double alpha, Double power,
                                          Double srmAlpha, List<VariantReq> variants, List<GuardrailReq> guardrails) {}
    public record TransitionRequest(String toStatus, String changedBy, String reason) {}
    public record DecisionRequest(String decision, String chosenVariantId, String decidedBy, Boolean srmAcknowledged,
                                  Boolean guardrailsAcknowledged, String rationale) {}

    private static final Set<String> TEAMS = Set.of("GROWTH", "MARKETING_OPS", "SALES_OPS", "PRODUCT", "SUPPORT_OPS", "PLATFORM");
    private static final String LATEST_RUN = "(SELECT r2.run_id FROM analysis_run r2 WHERE r2.experiment_id = e.experiment_id ORDER BY r2.computed_at DESC, r2.run_id DESC LIMIT 1)";

    private final Db db;
    private final LifecycleService lifecycle;
    private final AnalysisService analysis;

    public ExperimentService(Db db, LifecycleService lifecycle, AnalysisService analysis) {
        this.db = db;
        this.lifecycle = lifecycle;
        this.analysis = analysis;
    }

    // ------------------------------------------------------------------ queries
    public List<Map<String, Object>> list() {
        return db.rows("SELECT e.experiment_id, e.name, e.owner_team, e.status, e.start_at, e.end_at, e.primary_metric_def_id, e.planned_sample_size, "
            + "(SELECT COUNT(*) FROM variant v WHERE v.experiment_id = e.experiment_id) n_variants, "
            + "(SELECT COUNT(DISTINCT a.user_id) FROM assignment a WHERE a.experiment_id = e.experiment_id) n_assigned_users, "
            + "r.run_id, r.computed_at, r.recommendation, r.recommended_variant_id, r.srm_status, r.srm_p_value, r.balance_status, r.metric_version_note, "
            + "(SELECT COUNT(*) FROM experiment_result x WHERE x.run_id = r.run_id AND x.guardrail_status = 'FAIL') guardrail_fails, "
            + "(SELECT COUNT(*) FROM experiment_result x WHERE x.run_id = r.run_id AND x.guardrail_status = 'WARN') guardrail_warns, "
            + "(SELECT x.rel_diff FROM experiment_result x JOIN variant v ON v.variant_id = x.variant_id WHERE x.run_id = r.run_id AND x.metric_role = 'PRIMARY' AND v.is_control = 0 ORDER BY v.variant_key LIMIT 1) primary_rel_diff, "
            + "d.decision, d.decided_at, d.srm_acknowledged "
            + "FROM experiment e LEFT JOIN analysis_run r ON r.run_id = " + LATEST_RUN + " "
            + "LEFT JOIN experiment_decision d ON d.decision_id = (SELECT d2.decision_id FROM experiment_decision d2 WHERE d2.experiment_id = e.experiment_id ORDER BY d2.decided_at DESC, d2.decision_id DESC LIMIT 1) "
            + "ORDER BY e.experiment_id");
    }

    public Map<String, Object> detail(String id) {
        Map<String, Object> exp = db.row("SELECT * FROM experiment WHERE experiment_id = ?", id);
        if (exp == null) throw ApiException.notFound("Experiment " + id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experiment", exp);
        Map<String, Object> pm = db.row("SELECT * FROM metric_definition WHERE metric_def_id = ?", exp.get("primary_metric_def_id"));
        out.put("primary_metric", pm);
        out.put("metric_versions", db.rows("SELECT metric_def_id, version, name, window_days, effective_from, created_by, change_reason FROM metric_definition WHERE metric_code = ? ORDER BY version", pm.get("metric_code")));
        out.put("variants", db.rows("SELECT v.*, "
            + "(SELECT COUNT(DISTINCT a.user_id) FROM assignment a WHERE a.variant_id = v.variant_id) n_assigned_users, "
            + "(SELECT COUNT(*) FROM exposure_event x WHERE x.variant_id = v.variant_id) n_exposures "
            + "FROM variant v WHERE v.experiment_id = ? ORDER BY v.variant_key", id));
        out.put("guardrails", db.rows("SELECT g.*, m.metric_code, m.name metric_name, m.unit, m.desired_direction, m.window_days FROM guardrail_metric g JOIN metric_definition m ON m.metric_def_id = g.metric_def_id WHERE g.experiment_id = ? ORDER BY g.guardrail_id", id));
        out.put("status_history", db.rows("SELECT * FROM experiment_status_event WHERE experiment_id = ? ORDER BY changed_at, status_event_id", id));
        out.put("runs", db.rows("SELECT run_id, computed_at, triggered_by, recommendation, recommended_variant_id, srm_status, srm_p_value, balance_status, n_analyzed_users FROM analysis_run WHERE experiment_id = ? ORDER BY computed_at DESC, run_id DESC", id));
        Map<String, Object> latest = db.row("SELECT * FROM analysis_run WHERE experiment_id = ? ORDER BY computed_at DESC, run_id DESC LIMIT 1", id);
        out.put("latest_run", latest);
        out.put("latest_results", latest == null ? List.of() : results((String) latest.get("run_id")));
        out.put("decisions", db.rows("SELECT * FROM experiment_decision WHERE experiment_id = ? ORDER BY decided_at DESC, decision_id DESC", id));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("eligible_users", db.count("SELECT COUNT(*) FROM experiment_eligibility WHERE experiment_id = ?", id));
        counts.put("assigned_users", db.count("SELECT COUNT(DISTINCT user_id) FROM assignment WHERE experiment_id = ?", id));
        counts.put("assignments", db.count("SELECT COUNT(*) FROM assignment WHERE experiment_id = ?", id));
        counts.put("exposures", db.count("SELECT COUNT(*) FROM exposure_event WHERE experiment_id = ?", id));
        counts.put("conversion_events", db.rows("SELECT event_type, COUNT(*) n FROM conversion_event WHERE experiment_id = ? GROUP BY event_type ORDER BY event_type", id));
        out.put("counts", counts);
        out.put("issues", db.rows("SELECT issue_type, COUNT(*) n, SUBSTRING_INDEX(GROUP_CONCAT(entity_id ORDER BY entity_id SEPARATOR ','), ',', 5) sample_ids, MIN(details) example "
            + "FROM detected_issue WHERE experiment_id = ? AND validation_run_id = (SELECT MAX(validation_run_id) FROM validation_run) GROUP BY issue_type ORDER BY issue_type", id));
        return out;
    }

    public List<Map<String, Object>> results(String runId) {
        return db.rows("SELECT x.*, v.variant_key, v.name variant_name, v.is_control, m.metric_code, m.name metric_name, m.unit, m.metric_type, m.desired_direction, "
            + "g.max_relative_degradation, g.severity FROM experiment_result x JOIN variant v ON v.variant_id = x.variant_id "
            + "JOIN metric_definition m ON m.metric_def_id = x.metric_def_id LEFT JOIN guardrail_metric g ON g.guardrail_id = x.guardrail_id "
            + "WHERE x.run_id = ? ORDER BY x.metric_role DESC, x.guardrail_id, v.variant_key", runId);
    }

    public Map<String, Object> run(String runId) {
        Map<String, Object> r = db.row("SELECT * FROM analysis_run WHERE run_id = ?", runId);
        if (r == null) throw ApiException.notFound("Run " + runId);
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("results", results(runId));
        return out;
    }

    public List<Map<String, Object>> metrics() {
        return db.rows("SELECT m.*, (SELECT MIN(n.effective_from) FROM metric_definition n WHERE n.metric_code = m.metric_code AND n.version > m.version) effective_to "
            + "FROM metric_definition m ORDER BY m.metric_code, m.version");
    }

    public Map<String, Object> sampleSize(double baseline, double mde, double alpha, double power, int arms, double minSharePct) {
        if (baseline <= 0 || baseline >= 1) throw ApiException.invalid("BAD_BASELINE", "baseline must be in (0,1)");
        if (mde <= 0) throw ApiException.invalid("BAD_MDE", "mde must be > 0");
        if (arms < 2) throw ApiException.invalid("BAD_ARMS", "at least 2 arms");
        double alphaAdj = arms > 2 ? alpha / (arms - 1) : alpha;
        int perArm = Stats.sampleSizePerArm(baseline, mde, alphaAdj, power);
        int total = (int) Math.ceil(perArm / (minSharePct / 100.0));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseline", baseline);
        m.put("mde_relative", mde);
        m.put("alpha", alpha);
        m.put("alpha_adjusted", alphaAdj);
        m.put("power", power);
        m.put("arms", arms);
        m.put("per_arm", perArm);
        m.put("total", total);
        m.put("method", "Two-proportion z-test, normal approximation; Bonferroni across treatment arms; total sized so the smallest arm reaches per_arm");
        return m;
    }

    // ------------------------------------------------------------------ design
    @Transactional
    public Map<String, Object> create(CreateExperimentRequest r) {
        List<String> errors = new ArrayList<>();
        if (blank(r.name())) errors.add("name is required");
        if (blank(r.hypothesis()) || r.hypothesis().length() < 20) errors.add("hypothesis must be at least 20 characters");
        if (r.ownerTeam() == null || !TEAMS.contains(r.ownerTeam())) errors.add("ownerTeam must be one of " + TEAMS);
        if (blank(r.requestedBy())) errors.add("requestedBy is required");
        Map<String, Object> pm = r.primaryMetricDefId() == null ? null : db.row("SELECT * FROM metric_definition WHERE metric_def_id = ?", r.primaryMetricDefId());
        if (pm == null) errors.add("primaryMetricDefId does not exist");
        else if (!"PROPORTION".equals(pm.get("metric_type"))) errors.add("primary metric must be a PROPORTION metric");
        if (r.startAt() == null || r.endAt() == null || !r.endAt().isAfter(r.startAt())) errors.add("endAt must be after startAt");
        double alpha = r.alpha() == null ? 0.05 : r.alpha();
        double power = r.power() == null ? 0.8 : r.power();
        double srmAlpha = r.srmAlpha() == null ? 0.001 : r.srmAlpha();
        if (alpha <= 0 || alpha > 0.2) errors.add("alpha must be in (0, 0.2]");
        if (power < 0.5 || power >= 1) errors.add("power must be in [0.5, 1)");
        if (r.baselineRate() == null || r.baselineRate() <= 0 || r.baselineRate() >= 1) errors.add("baselineRate must be in (0,1)");
        if (r.mdeRelative() == null || r.mdeRelative() <= 0) errors.add("mdeRelative must be > 0");
        List<VariantReq> vs = r.variants() == null ? List.of() : r.variants();
        if (vs.size() < 2 || vs.size() > 5) errors.add("2 to 5 variants required (first is control)");
        double sum = 0;
        for (VariantReq v : vs) {
            if (blank(v.name())) errors.add("every variant needs a name");
            if (v.allocationPct() == null || v.allocationPct() <= 0) errors.add("every allocation must be > 0");
            else sum += v.allocationPct();
        }
        if (!vs.isEmpty() && Math.abs(sum - 100) > 1e-6) errors.add("allocations must sum to 100 (got " + sum + ")");
        List<GuardrailReq> gs = r.guardrails() == null ? List.of() : r.guardrails();
        if (gs.isEmpty()) errors.add("at least one guardrail metric is required");
        Set<String> seen = new HashSet<>();
        for (GuardrailReq g : gs) {
            if (g.metricDefId() == null || db.count("SELECT COUNT(*) FROM metric_definition WHERE metric_def_id = ?", g.metricDefId()) == 0) errors.add("guardrail metric " + g.metricDefId() + " does not exist");
            if (!seen.add(String.valueOf(g.metricDefId()))) errors.add("duplicate guardrail " + g.metricDefId());
            if (g.metricDefId() != null && g.metricDefId().equals(r.primaryMetricDefId())) errors.add("guardrail cannot be the primary metric");
            if (g.maxRelativeDegradation() == null || g.maxRelativeDegradation() <= 0 || g.maxRelativeDegradation() > 1) errors.add("guardrail tolerance must be in (0,1]");
            if (g.severity() != null && !Set.of("BLOCKING", "ADVISORY").contains(g.severity())) errors.add("severity must be BLOCKING or ADVISORY");
        }
        if (!errors.isEmpty()) throw ApiException.invalid("DESIGN_INVALID", String.join("; ", errors));

        double minShare = vs.stream().mapToDouble(VariantReq::allocationPct).min().orElse(50);
        int perArm = Stats.sampleSizePerArm(r.baselineRate(), r.mdeRelative(), vs.size() > 2 ? alpha / (vs.size() - 1) : alpha, power);
        int planned = (int) Math.ceil(perArm / (minShare / 100.0));
        String id = db.nextId("experiment", "experiment_id", "EXP-2026-", 6);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long seed = db.count("SELECT COALESCE(MAX(seed), 20261022) FROM generation_run");
        db.update("INSERT INTO experiment (experiment_id, name, hypothesis, owner_team, requested_by, primary_metric_def_id, randomization_unit, allocation_method, randomization_seed, start_at, end_at, baseline_rate, mde_relative, alpha, power, planned_sample_size, srm_alpha, analysis_population, multiple_testing, analysis_plan_locked_at, status, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, r.name(), r.hypothesis(), r.ownerTeam(), r.requestedBy(), r.primaryMetricDefId(), "USER", "HASH_SEEDED", seed,
            r.startAt(), r.endAt(), r.baselineRate(), r.mdeRelative(), alpha, power, planned, srmAlpha, "ITT",
            vs.size() > 2 ? "BONFERRONI" : "NONE", null, "DRAFT", now);
        for (int i = 0; i < vs.size(); i++) {
            String key = String.valueOf((char) ('A' + i));
            db.update("INSERT INTO variant (variant_id, experiment_id, variant_key, name, description, is_control, planned_allocation_pct) VALUES (?,?,?,?,?,?,?)",
                "VAR-" + id + "-" + key, id, key, vs.get(i).name(), vs.get(i).description(), i == 0, vs.get(i).allocationPct());
        }
        for (int i = 0; i < gs.size(); i++) {
            db.update("INSERT INTO guardrail_metric (guardrail_id, experiment_id, metric_def_id, max_relative_degradation, severity) VALUES (?,?,?,?,?)",
                String.format("GRD-%s-%02d", id, i + 1), id, gs.get(i).metricDefId(), gs.get(i).maxRelativeDegradation(),
                gs.get(i).severity() == null ? "BLOCKING" : gs.get(i).severity());
        }
        String sev = db.nextId("experiment_status_event", "status_event_id", "SEV-2026-", 6);
        db.update("INSERT INTO experiment_status_event (status_event_id, experiment_id, from_status, to_status, changed_at, changed_by, reason) VALUES (?,?,?,?,?,?,?)",
            sev, id, null, "DRAFT", now, r.requestedBy(), "Experiment designed in UI");
        return detail(id);
    }

    @Transactional
    public Map<String, Object> transition(String id, TransitionRequest req) {
        if (req.toStatus() == null) throw ApiException.invalid("BAD_STATUS", "toStatus is required");
        if (Set.of("ANALYZED", "DECIDED").contains(req.toStatus())) {
            throw ApiException.conflict("USE_WORKFLOW_ENDPOINT", req.toStatus() + " is reached by running an analysis / recording a decision");
        }
        String by = blank(req.changedBy()) ? "ui.user" : req.changedBy();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Map<String, Object> exp = db.row("SELECT * FROM experiment WHERE experiment_id = ?", id);
        if (exp == null) throw ApiException.notFound("Experiment " + id);
        if ("DESIGNED".equals(req.toStatus())) {
            LocalDateTime start = (LocalDateTime) exp.get("start_at");
            if (start != null && now.isAfter(start)) {
                throw ApiException.invalid("PLAN_LOCK_TOO_LATE", "The analysis plan must be locked before start_at (" + start + "); move the start date first");
            }
        }
        lifecycle.transition(id, req.toStatus(), now, by, req.reason());
        if ("DESIGNED".equals(req.toStatus())) {
            db.update("UPDATE experiment SET analysis_plan_locked_at = ? WHERE experiment_id = ? AND analysis_plan_locked_at IS NULL", now, id);
        }
        return detail(id);
    }

    public Map<String, Object> analyze(String id, String by) {
        return analysis.analyze(id, LocalDateTime.now().withNano(0), blank(by) ? "ui.analyst" : by);
    }

    @Transactional
    public Map<String, Object> decide(String id, DecisionRequest r) {
        String status = lifecycle.currentStatus(id);
        if (!Set.of("ANALYZED", "DECIDED").contains(status)) throw ApiException.conflict("NOT_ANALYZED", "A decision needs an analysed experiment (status " + status + ")");
        if (r.decision() == null || !Set.of("SHIP", "DO_NOT_SHIP", "ITERATE", "ROLLBACK").contains(r.decision())) throw ApiException.invalid("BAD_DECISION", "decision must be SHIP, DO_NOT_SHIP, ITERATE or ROLLBACK");
        if (blank(r.decidedBy())) throw ApiException.invalid("DECIDER_REQUIRED", "decidedBy is required");
        Map<String, Object> run = db.row("SELECT * FROM analysis_run WHERE experiment_id = ? ORDER BY computed_at DESC, run_id DESC LIMIT 1", id);
        boolean srmAck = Boolean.TRUE.equals(r.srmAcknowledged());
        boolean grAck = Boolean.TRUE.equals(r.guardrailsAcknowledged());
        List<String> warnings = new ArrayList<>();
        if ("SHIP".equals(r.decision())) {
            Map<String, Object> v = r.chosenVariantId() == null ? null : db.row("SELECT * FROM variant WHERE variant_id = ? AND experiment_id = ?", r.chosenVariantId(), id);
            if (v == null || Boolean.TRUE.equals(v.get("is_control"))) throw ApiException.invalid("BAD_VARIANT", "SHIP requires a treatment variant of this experiment");
            if ("FAIL".equals(run.get("srm_status")) && !srmAck) {
                throw ApiException.invalid("SRM_NOT_ACKNOWLEDGED", "Latest run " + run.get("run_id") + " failed the sample-ratio check (p=" + run.get("srm_p_value") + "). Results are not trustworthy; acknowledge explicitly or choose ITERATE.");
            }
            long blocking = db.count("SELECT COUNT(*) FROM experiment_result x JOIN guardrail_metric g ON g.guardrail_id = x.guardrail_id WHERE x.run_id = ? AND x.variant_id = ? AND x.guardrail_status = 'FAIL' AND g.severity = 'BLOCKING'", run.get("run_id"), r.chosenVariantId());
            if (blocking > 0 && !grAck) throw ApiException.invalid("GUARDRAIL_FAILED", blocking + " blocking guardrail(s) failed for " + r.chosenVariantId() + "; acknowledge explicitly to override.");
            if (srmAck && "FAIL".equals(run.get("srm_status"))) warnings.add("Shipping despite SRM failure (acknowledged)");
            if (blocking > 0) warnings.add("Shipping despite blocking guardrail failure (acknowledged)");
        }
        String did = db.nextId("experiment_decision", "decision_id", "DEC-2026-", 6);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        db.update("INSERT INTO experiment_decision (decision_id, experiment_id, run_id, decision, chosen_variant_id, decided_by, decided_at, srm_acknowledged, guardrails_acknowledged, rationale) VALUES (?,?,?,?,?,?,?,?,?,?)",
            did, id, run.get("run_id"), r.decision(), "SHIP".equals(r.decision()) ? r.chosenVariantId() : null, r.decidedBy(), now, srmAck, grAck, r.rationale());
        if ("ANALYZED".equals(status)) lifecycle.transition(id, "DECIDED", now, r.decidedBy(), "Decision " + did + ": " + r.decision());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision_id", did);
        out.put("warnings", warnings);
        out.put("experiment", detail(id));
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
