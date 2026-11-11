package com.expplatform.service;

import com.expplatform.repository.Db;
import com.expplatform.schema.Schema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validation = (1) integrity checks that must have zero violations, (2) anomaly detectors that
 * work ONLY on source tables, (3) evaluation of detectors and analysis results against ground truth.
 */
@Service
public class ValidationService {

    private final Db db;
    private final ObjectMapper mapper;

    public ValidationService(Db db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public record Check(String category, String name, String target, String status, long violations, Long expected, String details) {}

    private record Issue(String type, String entityType, String entityId, String experimentId, String details) {}

    /** Issue types the detectors look for (TELEMETRY_DROPPED is undetectable from source data by design). */
    public static final List<String> DETECTABLE = List.of("MULTIPLE_VARIANT_ASSIGNMENT", "EXPOSURE_BEFORE_ASSIGNMENT",
        "OUTCOME_WINDOW_INCONSISTENT", "SAMPLE_RATIO_MISMATCH", "SRM_IGNORED", "METRIC_DEFINITION_CHANGED_AFTER_RESULT",
        "GUARDRAIL_REGRESSION", "NONCOMPLIANCE", "MISSING_TELEMETRY", "CONVERSION_OUTSIDE_WINDOW");

    private static final String LATEST_RUN = "(SELECT r2.run_id FROM analysis_run r2 WHERE r2.experiment_id = e.experiment_id ORDER BY r2.computed_at DESC, r2.run_id DESC LIMIT 1)";

    public synchronized Map<String, Object> run(String genRunId) {
        List<Check> checks = new ArrayList<>();
        integrityChecks(checks);
        List<Issue> issues = detectAnomalies();
        Map<String, Object> evaluation = evaluate(issues, checks);

        int passed = 0, failed = 0, warnings = 0;
        for (Check c : checks) {
            switch (c.status()) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                default -> warnings++;
            }
        }
        String runId = db.nextId("validation_run", "validation_run_id", "VAL-2026-", 6);
        String runNum = runId.substring("VAL-2026-".length());
        String evalJson;
        try { evalJson = mapper.writeValueAsString(evaluation); } catch (Exception e) { throw new IllegalStateException(e); }
        db.update("INSERT INTO validation_run (validation_run_id, gen_run_id, run_at, passed, failed, warnings, evaluation_json) VALUES (?,?,?,?,?,?,?)",
            runId, genRunId, LocalDateTime.now().withNano(0), passed, failed, warnings, evalJson);
        List<Object[]> checkRows = new ArrayList<>();
        int i = 0;
        for (Check c : checks) {
            checkRows.add(new Object[]{String.format("CHK-%s-%04d", runNum, ++i), runId, c.category(), c.name(), c.target(), c.status(),
                (int) c.violations(), c.expected() == null ? null : c.expected().intValue(), trunc(c.details(), 1000)});
        }
        db.batchInsert("validation_check", List.of("check_id", "validation_run_id", "category", "check_name", "target", "status", "violations", "expected", "details"), checkRows);
        List<Object[]> issueRows = new ArrayList<>();
        int j = 0;
        for (Issue is : issues) {
            issueRows.add(new Object[]{String.format("ISS-%s-%06d", runNum, ++j), runId, is.type(), is.entityType(), is.entityId(), is.experimentId(), trunc(is.details(), 500)});
        }
        db.batchInsert("detected_issue", List.of("issue_id", "validation_run_id", "issue_type", "entity_type", "entity_id", "experiment_id", "details"), issueRows);
        return latest();
    }

    public Map<String, Object> latest() {
        Map<String, Object> run = db.row("SELECT * FROM validation_run ORDER BY validation_run_id DESC LIMIT 1");
        if (run == null) return Map.of("message", "No validation run yet");
        Map<String, Object> out = new LinkedHashMap<>(run);
        try {
            out.put("evaluation", mapper.readValue((String) run.remove("evaluation_json"), new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            out.put("evaluation", Map.of());
        }
        out.remove("evaluation_json");
        out.put("checks", db.rows("SELECT category, check_name, target, status, violations, expected, details FROM validation_check WHERE validation_run_id = ? ORDER BY check_id", run.get("validation_run_id")));
        return out;
    }

    // ================================================================== integrity
    private void integrityChecks(List<Check> out) {
        for (Schema.Table t : Schema.allTables()) {
            String n = t.name();
            zero(out, "PRIMARY_KEY", "Primary key unique", n, "SELECT COUNT(*) - COUNT(DISTINCT " + t.primaryKey() + ") FROM " + n);
            List<Schema.Column> req = t.columns().stream().filter(Schema.Column::required).toList();
            String reqSql = "SELECT COALESCE(" + req.stream().map(c -> "SUM(" + c.name() + " IS NULL)").collect(Collectors.joining("+")) + ",0) FROM " + n;
            zero(out, "REQUIRED", "Required fields not null (" + req.size() + " columns)", n, reqSql);
            for (String[] u : t.compositeUniques()) {
                String cols = String.join(", ", u);
                zero(out, "UNIQUENESS", "Unique (" + cols + ")", n, "SELECT COUNT(*) FROM (SELECT " + cols + " FROM " + n + " GROUP BY " + cols + " HAVING COUNT(*) > 1) x");
            }
            for (Schema.Column c : t.columns()) {
                if (!c.allowed().isEmpty()) {
                    String in = c.allowed().stream().map(v -> "'" + v + "'").collect(Collectors.joining(","));
                    zero(out, "ENUM", c.name() + " in allowed values", n, "SELECT COUNT(*) FROM " + n + " WHERE " + c.name() + " IS NOT NULL AND " + c.name() + " NOT IN (" + in + ")");
                }
                if (c.pattern() != null) {
                    zero(out, "ID_FORMAT", c.name() + " matches " + c.pattern(), n, "SELECT COUNT(*) FROM " + n + " WHERE " + c.name() + " IS NOT NULL AND NOT REGEXP_LIKE(" + c.name() + ", ?, 'c')", c.pattern());
                }
                if (c.fkTable() != null) {
                    zero(out, "FOREIGN_KEY", c.name() + " -> " + c.fkTable() + "." + c.fkColumn(), n,
                        "SELECT COUNT(*) FROM " + n + " c LEFT JOIN " + c.fkTable() + " p ON p." + c.fkColumn() + " = c." + c.name()
                            + " WHERE c." + c.name() + " IS NOT NULL AND p." + c.fkColumn() + " IS NULL");
                }
            }
        }

        // ---- date ordering
        zero(out, "DATE_ORDER", "experiment.end_at > start_at", "experiment", "SELECT COUNT(*) FROM experiment WHERE end_at <= start_at");
        zero(out, "DATE_ORDER", "analysis plan locked before start", "experiment", "SELECT COUNT(*) FROM experiment WHERE analysis_plan_locked_at > start_at");
        zero(out, "DATE_ORDER", "assigned_at within enrollment window", "assignment",
            "SELECT COUNT(*) FROM assignment a JOIN experiment e ON e.experiment_id = a.experiment_id WHERE a.assigned_at < e.start_at OR a.assigned_at > e.end_at");
        zero(out, "DATE_ORDER", "eligible_at <= assigned_at", "assignment",
            "SELECT COUNT(*) FROM assignment a JOIN experiment_eligibility el ON el.experiment_id = a.experiment_id AND el.user_id = a.user_id WHERE el.eligible_at > a.assigned_at");
        zero(out, "DATE_ORDER", "signup_date before experiment start", "audience_member",
            "SELECT COUNT(*) FROM experiment_eligibility el JOIN audience_member m ON m.user_id = el.user_id JOIN experiment e ON e.experiment_id = el.experiment_id WHERE m.signup_date >= DATE(e.start_at)");
        zero(out, "DATE_ORDER", "conversion ingested_at >= occurred_at", "conversion_event", "SELECT COUNT(*) FROM conversion_event WHERE ingested_at < occurred_at");
        zero(out, "DATE_ORDER", "conversion occurred_at >= assigned_at", "conversion_event",
            "SELECT COUNT(*) FROM conversion_event c JOIN assignment a ON a.assignment_id = c.assignment_id WHERE c.occurred_at < a.assigned_at");
        zero(out, "DATE_ORDER", "analysis computed after enrollment end", "analysis_run",
            "SELECT COUNT(*) FROM analysis_run r JOIN experiment e ON e.experiment_id = r.experiment_id WHERE r.computed_at < e.end_at");
        zero(out, "DATE_ORDER", "decided_at >= run.computed_at", "experiment_decision",
            "SELECT COUNT(*) FROM experiment_decision d JOIN analysis_run r ON r.run_id = d.run_id WHERE d.decided_at < r.computed_at");
        zero(out, "DATE_ORDER", "status events chronological", "experiment_status_event",
            "SELECT COUNT(*) FROM (SELECT changed_at, LAG(changed_at) OVER (PARTITION BY experiment_id ORDER BY changed_at, status_event_id) prev FROM experiment_status_event) x WHERE prev > changed_at");

        // ---- arithmetic reconciliation
        zero(out, "RECONCILIATION", "planned allocation sums to 100 per experiment", "variant",
            "SELECT COUNT(*) FROM (SELECT experiment_id FROM variant GROUP BY experiment_id HAVING ABS(SUM(planned_allocation_pct) - 100) > 0.000001) x");
        zero(out, "RECONCILIATION", "exactly one control per experiment", "variant",
            "SELECT COUNT(*) FROM experiment e WHERE (SELECT COUNT(*) FROM variant v WHERE v.experiment_id = e.experiment_id AND v.is_control = 1) <> 1");
        zero(out, "RECONCILIATION", "metric_value = numerator / n_users", "experiment_result",
            "SELECT COUNT(*) FROM experiment_result WHERE n_users > 0 AND ABS(metric_value - numerator / n_users) > 0.000000001");
        zero(out, "RECONCILIATION", "abs_diff = metric_value - control_value", "experiment_result",
            "SELECT COUNT(*) FROM experiment_result WHERE abs_diff IS NOT NULL AND ABS(abs_diff - (metric_value - control_value)) > 0.000000001");
        zero(out, "RECONCILIATION", "control_value equals the control row of the same run/metric", "experiment_result",
            "SELECT COUNT(*) FROM experiment_result t JOIN experiment_result c ON c.run_id = t.run_id AND c.metric_def_id = t.metric_def_id AND c.metric_role = t.metric_role "
                + "AND (c.guardrail_id <=> t.guardrail_id) JOIN variant v ON v.variant_id = c.variant_id AND v.is_control = 1 "
                + "WHERE t.control_value IS NOT NULL AND ABS(t.control_value - c.metric_value) > 0.000000001");
        zero(out, "RECONCILIATION", "sum(primary n_users) = run.n_analyzed_users", "analysis_run",
            "SELECT COUNT(*) FROM analysis_run r WHERE r.n_analyzed_users <> (SELECT COALESCE(SUM(x.n_users),0) FROM experiment_result x WHERE x.run_id = r.run_id AND x.metric_role = 'PRIMARY')");
        zero(out, "RECONCILIATION", "n_analyzed = n_assigned - n_excluded_multi_assignment", "analysis_run",
            "SELECT COUNT(*) FROM analysis_run WHERE n_analyzed_users <> n_assigned_users - n_excluded_multi_assignment");
        zero(out, "RECONCILIATION", "run.n_assigned_users = distinct assigned users", "analysis_run",
            "SELECT COUNT(*) FROM analysis_run r WHERE r.n_assigned_users <> (SELECT COUNT(DISTINCT a.user_id) FROM assignment a WHERE a.experiment_id = r.experiment_id)");
        // Independent SQL re-implementation of the ITT population (users with >1 distinct variant excluded)
        String pop = "WITH multi AS (SELECT experiment_id, user_id FROM assignment GROUP BY experiment_id, user_id HAVING COUNT(DISTINCT variant_id) > 1), "
            + "pop AS (SELECT a.* FROM assignment a LEFT JOIN multi mu ON mu.experiment_id = a.experiment_id AND mu.user_id = a.user_id WHERE mu.user_id IS NULL) ";
        zero(out, "RECONCILIATION", "converted users re-counted in SQL match result numerators (PROPORTION metrics)", "experiment_result",
            pop + ", recount AS (SELECT x.result_id, COUNT(DISTINCT p.user_id) n FROM experiment_result x "
                + "JOIN analysis_run ar ON ar.run_id = x.run_id JOIN metric_definition m ON m.metric_def_id = x.metric_def_id "
                + "JOIN pop p ON p.experiment_id = x.experiment_id AND p.variant_id = x.variant_id "
                + "JOIN conversion_event c ON c.user_id = p.user_id AND c.experiment_id = p.experiment_id AND c.event_type = m.event_type "
                + "WHERE m.metric_type = 'PROPORTION' AND c.ingested_at <= ar.data_cutoff_at AND c.occurred_at >= p.assigned_at "
                + "AND c.occurred_at < DATE_ADD(p.assigned_at, INTERVAL m.window_days DAY) GROUP BY x.result_id) "
                + "SELECT COUNT(*) FROM experiment_result x JOIN metric_definition m ON m.metric_def_id = x.metric_def_id "
                + "LEFT JOIN recount rc ON rc.result_id = x.result_id WHERE m.metric_type = 'PROPORTION' AND x.numerator <> COALESCE(rc.n, 0)");
        zero(out, "RECONCILIATION", "primary analysed users per variant re-counted in SQL", "experiment_result",
            pop + "SELECT COUNT(*) FROM experiment_result x LEFT JOIN (SELECT experiment_id, variant_id, COUNT(DISTINCT user_id) n FROM pop GROUP BY experiment_id, variant_id) pc "
                + "ON pc.experiment_id = x.experiment_id AND pc.variant_id = x.variant_id WHERE x.metric_role = 'PRIMARY' AND x.n_users <> COALESCE(pc.n, 0)");

        // ---- lifecycle
        List<Map<String, Object>> events = db.rows("SELECT experiment_id, from_status, to_status FROM experiment_status_event ORDER BY experiment_id, changed_at, status_event_id");
        long badTransitions = 0, badChain = 0;
        Map<String, String> last = new LinkedHashMap<>();
        for (Map<String, Object> ev : events) {
            String exp = (String) ev.get("experiment_id");
            String from = (String) ev.get("from_status"), to = (String) ev.get("to_status");
            if (!LifecycleService.allowed(from, to)) badTransitions++;
            String prev = last.get(exp);
            if (prev == null ? from != null : !prev.equals(from)) badChain++;
            last.put(exp, to);
        }
        out.add(check("LIFECYCLE", "transitions follow the state machine", "experiment_status_event", badTransitions, null));
        out.add(check("LIFECYCLE", "from_status equals previous to_status", "experiment_status_event", badChain, null));
        long mismatch = 0;
        for (Map<String, Object> e : db.rows("SELECT experiment_id, status FROM experiment")) {
            if (!e.get("status").equals(last.get((String) e.get("experiment_id")))) mismatch++;
        }
        out.add(check("LIFECYCLE", "experiment.status = latest status event", "experiment", mismatch, null));
        zero(out, "LIFECYCLE", "ANALYZED/DECIDED/ARCHIVED experiments have an analysis run", "experiment",
            "SELECT COUNT(*) FROM experiment e WHERE e.status IN ('ANALYZED','DECIDED','ARCHIVED') AND NOT EXISTS (SELECT 1 FROM analysis_run r WHERE r.experiment_id = e.experiment_id)");
        zero(out, "LIFECYCLE", "DECIDED/ARCHIVED experiments have a decision", "experiment",
            "SELECT COUNT(*) FROM experiment e WHERE e.status IN ('DECIDED','ARCHIVED') AND NOT EXISTS (SELECT 1 FROM experiment_decision d WHERE d.experiment_id = e.experiment_id)");
        zero(out, "LIFECYCLE", "no analysis before enrollment stopped", "analysis_run",
            "SELECT COUNT(*) FROM analysis_run r JOIN experiment e ON e.experiment_id = r.experiment_id WHERE e.status IN ('DRAFT','DESIGNED','RUNNING','CANCELLED')");
        zero(out, "LIFECYCLE", "no assignments for DRAFT/DESIGNED experiments", "assignment",
            "SELECT COUNT(*) FROM assignment a JOIN experiment e ON e.experiment_id = a.experiment_id WHERE e.status IN ('DRAFT','DESIGNED')");

        // ---- duplicates
        zero(out, "DUPLICATES", "no duplicate conversion events (assignment, type, occurred_at)", "conversion_event",
            "SELECT COUNT(*) FROM (SELECT assignment_id FROM conversion_event GROUP BY assignment_id, event_type, occurred_at HAVING COUNT(*) > 1) x");
        zero(out, "DUPLICATES", "no duplicate exposures (assignment, variant, exposed_at)", "exposure_event",
            "SELECT COUNT(*) FROM (SELECT assignment_id FROM exposure_event GROUP BY assignment_id, variant_id, exposed_at HAVING COUNT(*) > 1) x");
        zero(out, "DUPLICATES", "no repeated identical assignment (experiment, user, variant)", "assignment",
            "SELECT COUNT(*) FROM (SELECT user_id FROM assignment GROUP BY experiment_id, user_id, variant_id HAVING COUNT(*) > 1) x");

        // ---- cross-entity business rules
        zero(out, "BUSINESS_RULE", "assigned variant belongs to the experiment", "assignment",
            "SELECT COUNT(*) FROM assignment a JOIN variant v ON v.variant_id = a.variant_id WHERE v.experiment_id <> a.experiment_id");
        zero(out, "BUSINESS_RULE", "exposure experiment/user/variant consistent with its assignment", "exposure_event",
            "SELECT COUNT(*) FROM exposure_event x JOIN assignment a ON a.assignment_id = x.assignment_id JOIN variant v ON v.variant_id = x.variant_id "
                + "WHERE x.experiment_id <> a.experiment_id OR x.user_id <> a.user_id OR v.experiment_id <> x.experiment_id");
        zero(out, "BUSINESS_RULE", "conversion experiment/user consistent with its assignment", "conversion_event",
            "SELECT COUNT(*) FROM conversion_event c JOIN assignment a ON a.assignment_id = c.assignment_id WHERE c.experiment_id <> a.experiment_id OR c.user_id <> a.user_id");
        zero(out, "BUSINESS_RULE", "every assignment has an eligibility record", "assignment",
            "SELECT COUNT(*) FROM assignment a LEFT JOIN experiment_eligibility el ON el.experiment_id = a.experiment_id AND el.user_id = a.user_id WHERE el.eligibility_id IS NULL");
        zero(out, "BUSINESS_RULE", "revenue > 0 iff CONVERSION/SUBSCRIPTION; currency iff revenue", "conversion_event",
            "SELECT COUNT(*) FROM conversion_event WHERE (event_type IN ('CONVERSION','SUBSCRIPTION')) <> (revenue_amount IS NOT NULL) OR revenue_amount <= 0 OR (currency IS NULL) <> (revenue_amount IS NULL)");
        zero(out, "BUSINESS_RULE", "page_load_ms NULL iff telemetry PARTIAL", "exposure_event",
            "SELECT COUNT(*) FROM exposure_event WHERE (page_load_ms IS NULL) <> (telemetry_status = 'PARTIAL')");
        zero(out, "BUSINESS_RULE", "bucket in 0..9999", "assignment", "SELECT COUNT(*) FROM assignment WHERE bucket < 0 OR bucket > 9999");
        zero(out, "BUSINESS_RULE", "pre_period_orders <= pre_period_sessions", "audience_member", "SELECT COUNT(*) FROM audience_member WHERE pre_period_orders > pre_period_sessions OR pre_period_orders < 0");
        zero(out, "BUSINESS_RULE", "PROPORTION metrics define event_type; MEAN metrics define value_field", "metric_definition",
            "SELECT COUNT(*) FROM metric_definition WHERE (metric_type = 'PROPORTION' AND event_type IS NULL) OR (metric_type = 'MEAN' AND value_field IS NULL)");
        zero(out, "BUSINESS_RULE", "every designed experiment declares >= 1 guardrail", "experiment",
            "SELECT COUNT(*) FROM experiment e WHERE e.status <> 'DRAFT' AND NOT EXISTS (SELECT 1 FROM guardrail_metric g WHERE g.experiment_id = e.experiment_id)");
        zero(out, "BUSINESS_RULE", "guardrail tolerance in (0, 1]", "guardrail_metric",
            "SELECT COUNT(*) FROM guardrail_metric WHERE max_relative_degradation <= 0 OR max_relative_degradation > 1");
        zero(out, "BUSINESS_RULE", "srm_status consistent with srm_p_value and experiment.srm_alpha", "analysis_run",
            "SELECT COUNT(*) FROM analysis_run r JOIN experiment e ON e.experiment_id = r.experiment_id WHERE (r.srm_p_value < e.srm_alpha) <> (r.srm_status = 'FAIL')");
    }

    // ================================================================== anomaly detection (source data only)
    private List<Issue> detectAnomalies() {
        List<Issue> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("SELECT a.assignment_id, a.experiment_id, a.user_id FROM assignment a "
                + "JOIN (SELECT experiment_id, user_id, MIN(assigned_at) first_at FROM assignment GROUP BY experiment_id, user_id HAVING COUNT(DISTINCT variant_id) > 1) m "
                + "ON m.experiment_id = a.experiment_id AND m.user_id = a.user_id WHERE a.assigned_at > m.first_at")) {
            out.add(new Issue("MULTIPLE_VARIANT_ASSIGNMENT", "ASSIGNMENT", s(r, "assignment_id"), s(r, "experiment_id"), "User " + s(r, "user_id") + " holds assignments to more than one variant"));
        }
        for (Map<String, Object> r : db.rows("SELECT x.exposure_id, x.experiment_id, x.exposed_at, a.assigned_at FROM exposure_event x JOIN assignment a ON a.assignment_id = x.assignment_id WHERE x.exposed_at < a.assigned_at")) {
            out.add(new Issue("EXPOSURE_BEFORE_ASSIGNMENT", "EXPOSURE_EVENT", s(r, "exposure_id"), s(r, "experiment_id"), "exposed_at " + r.get("exposed_at") + " < assigned_at " + r.get("assigned_at")));
        }
        String windowSql = "SELECT c.conversion_id, c.experiment_id, c.reported_in_window, "
            + "(c.occurred_at >= a.assigned_at AND c.occurred_at < DATE_ADD(a.assigned_at, INTERVAL m.window_days DAY)) AS in_window, m.window_days "
            + "FROM conversion_event c JOIN assignment a ON a.assignment_id = c.assignment_id JOIN experiment e ON e.experiment_id = c.experiment_id "
            + "JOIN metric_definition m ON m.metric_def_id = e.primary_metric_def_id WHERE c.event_type = m.event_type";
        for (Map<String, Object> r : db.rows("SELECT * FROM (" + windowSql + ") w WHERE w.in_window = 0 OR w.reported_in_window <> w.in_window")) {
            boolean reported = Boolean.TRUE.equals(r.get("reported_in_window"));
            boolean inWin = ((Number) r.get("in_window")).intValue() == 1;
            if (reported != inWin) {
                out.add(new Issue("OUTCOME_WINDOW_INCONSISTENT", "CONVERSION_EVENT", s(r, "conversion_id"), s(r, "experiment_id"),
                    "reported_in_window=" + reported + " but recomputed " + r.get("window_days") + "-day window says " + inWin));
            } else {
                out.add(new Issue("CONVERSION_OUTSIDE_WINDOW", "CONVERSION_EVENT", s(r, "conversion_id"), s(r, "experiment_id"),
                    "Outside the " + r.get("window_days") + "-day window; excluded from the primary metric"));
            }
        }
        for (Map<String, Object> r : db.rows("SELECT e.experiment_id, r.run_id, r.srm_p_value FROM experiment e JOIN analysis_run r ON r.run_id = " + LATEST_RUN + " WHERE r.srm_status = 'FAIL'")) {
            out.add(new Issue("SAMPLE_RATIO_MISMATCH", "EXPERIMENT", s(r, "experiment_id"), s(r, "experiment_id"), "Latest run " + s(r, "run_id") + " SRM p=" + r.get("srm_p_value")));
        }
        for (Map<String, Object> r : db.rows("SELECT e.experiment_id, r.run_id, GROUP_CONCAT(DISTINCT m.metric_code) metrics FROM experiment e JOIN analysis_run r ON r.run_id = " + LATEST_RUN
                + " JOIN experiment_result x ON x.run_id = r.run_id JOIN metric_definition m ON m.metric_def_id = x.metric_def_id WHERE x.guardrail_status = 'FAIL' GROUP BY e.experiment_id, r.run_id")) {
            out.add(new Issue("GUARDRAIL_REGRESSION", "EXPERIMENT", s(r, "experiment_id"), s(r, "experiment_id"), "Guardrail FAIL in " + s(r, "run_id") + ": " + s(r, "metrics")));
        }
        for (Map<String, Object> r : db.rows("SELECT d.decision_id, d.experiment_id, r.run_id, r.srm_p_value FROM experiment_decision d JOIN analysis_run r ON r.run_id = d.run_id "
                + "WHERE d.decision = 'SHIP' AND d.srm_acknowledged = 0 AND r.srm_status = 'FAIL'")) {
            out.add(new Issue("SRM_IGNORED", "EXPERIMENT_DECISION", s(r, "decision_id"), s(r, "experiment_id"), "SHIP decided on " + s(r, "run_id") + " with SRM p=" + r.get("srm_p_value") + " and no acknowledgement"));
        }
        for (Map<String, Object> r : db.rows("SELECT r.run_id, r.experiment_id, m2.metric_def_id newer, m2.effective_from FROM analysis_run r "
                + "JOIN metric_definition m ON m.metric_def_id = r.primary_metric_def_id "
                + "JOIN metric_definition m2 ON m2.metric_code = m.metric_code AND m2.version > m.version AND m2.effective_from > r.computed_at")) {
            out.add(new Issue("METRIC_DEFINITION_CHANGED_AFTER_RESULT", "ANALYSIS_RUN", s(r, "run_id"), s(r, "experiment_id"),
                "Result computed with " + "older version; " + s(r, "newer") + " effective " + r.get("effective_from") + " afterwards"));
        }
        for (Map<String, Object> r : db.rows("SELECT a.assignment_id, a.experiment_id, MIN(x.variant_id) shown FROM assignment a JOIN exposure_event x ON x.assignment_id = a.assignment_id "
                + "WHERE x.variant_id <> a.variant_id GROUP BY a.assignment_id, a.experiment_id")) {
            out.add(new Issue("NONCOMPLIANCE", "ASSIGNMENT", s(r, "assignment_id"), s(r, "experiment_id"), "Rendered " + s(r, "shown") + " instead of the assigned variant"));
        }
        for (Map<String, Object> r : db.rows("SELECT exposure_id, experiment_id FROM exposure_event WHERE telemetry_status = 'PARTIAL' OR page_load_ms IS NULL")) {
            out.add(new Issue("MISSING_TELEMETRY", "EXPOSURE_EVENT", s(r, "exposure_id"), s(r, "experiment_id"), "page_load_ms missing (PARTIAL telemetry)"));
        }
        return out;
    }

    // ================================================================== evaluation vs ground truth
    private Map<String, Object> evaluate(List<Issue> issues, List<Check> checks) {
        Map<String, Object> eval = new LinkedHashMap<>();
        List<Map<String, Object>> detection = new ArrayList<>();
        long tpAll = 0, fpAll = 0, fnAll = 0;
        for (String type : DETECTABLE) {
            Set<String> detected = issues.stream().filter(i -> i.type().equals(type)).map(Issue::entityId).collect(Collectors.toSet());
            Set<String> truth = new HashSet<>(db.jdbc.queryForList("SELECT entity_id FROM ground_truth WHERE issue_type = ?", String.class, type));
            long tp = detected.stream().filter(truth::contains).count();
            long fp = detected.size() - tp;
            long fn = truth.size() - tp;
            tpAll += tp; fpAll += fp; fnAll += fn;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("issue_type", type);
            m.put("ground_truth", truth.size());
            m.put("detected", detected.size());
            m.put("true_positive", tp);
            m.put("false_positive", fp);
            m.put("false_negative", fn);
            m.put("precision", detected.isEmpty() ? null : (double) tp / detected.size());
            m.put("recall", truth.isEmpty() ? null : (double) tp / truth.size());
            detection.add(m);
            checks.add(new Check("ANOMALY_DETECTION", "Detector " + type, "ground_truth",
                fp == 0 && fn == 0 ? "PASS" : "WARN", detected.size(), (long) truth.size(),
                "TP=" + tp + " FP=" + fp + " FN=" + fn + (type.equals("NONCOMPLIANCE") && fn > 0 ? " (FN = noncompliant users whose exposure telemetry was dropped)" : "")));
        }
        eval.put("detection", detection);
        eval.put("detection_overall", Map.of("true_positive", tpAll, "false_positive", fpAll, "false_negative", fnAll,
            "precision", tpAll + fpAll == 0 ? 0 : (double) tpAll / (tpAll + fpAll), "recall", tpAll + fnAll == 0 ? 0 : (double) tpAll / (tpAll + fnAll)));

        long dropped = db.count("SELECT COUNT(*) FROM ground_truth WHERE issue_type = 'TELEMETRY_DROPPED'");
        long neverExposed = db.count("SELECT COUNT(*) FROM assignment a WHERE NOT EXISTS (SELECT 1 FROM exposure_event x WHERE x.assignment_id = a.assignment_id)");
        eval.put("undetectable", Map.of("issue_type", "TELEMETRY_DROPPED", "ground_truth", dropped, "assignments_without_exposure", neverExposed,
            "note", "Dropped telemetry is indistinguishable from genuine non-exposure in source data; ITT analysis is unaffected."));

        // Experiment-level: recommendation accuracy + CI coverage of the true ITT lift
        List<Map<String, Object>> experiments = new ArrayList<>();
        int correct = 0, total = 0, covered = 0, ciTotal = 0;
        for (Map<String, Object> e : db.rows("SELECT e.experiment_id, e.name, r.run_id, r.recommendation, r.recommended_variant_id, r.srm_status, "
                + "(SELECT g.expected_value FROM ground_truth g WHERE g.experiment_id = e.experiment_id AND g.issue_type = 'EXPECTED_RECOMMENDATION' LIMIT 1) expected, "
                + "(SELECT g.expected_value FROM ground_truth g WHERE g.experiment_id = e.experiment_id AND g.issue_type = 'SCENARIO' LIMIT 1) scenario "
                + "FROM experiment e JOIN analysis_run r ON r.run_id = " + LATEST_RUN + " ORDER BY e.experiment_id")) {
            if (e.get("expected") == null) continue;
            String actual = "SHIP".equals(e.get("recommendation")) ? "SHIP:" + e.get("recommended_variant_id") : (String) e.get("recommendation");
            boolean ok = actual.equals(e.get("expected"));
            total++;
            if (ok) correct++;
            Map<String, Object> m = new LinkedHashMap<>(e);
            m.put("actual", actual);
            m.put("match", ok);
            List<Map<String, Object>> arms = new ArrayList<>();
            for (Map<String, Object> a : db.rows("SELECT g.entity_id variant_id, g.expected_value true_lift, x.rel_diff, x.rel_ci_low, x.rel_ci_high, x.p_value "
                    + "FROM ground_truth g JOIN experiment_result x ON x.variant_id = g.entity_id AND x.run_id = ? AND x.metric_role = 'PRIMARY' "
                    + "WHERE g.issue_type = 'TRUE_ITT_RELATIVE_LIFT' AND g.experiment_id = ?", e.get("run_id"), e.get("experiment_id"))) {
                double truth = Double.parseDouble((String) a.get("true_lift"));
                Map<String, Object> am = new LinkedHashMap<>(a);
                am.put("true_lift", truth);
                boolean cov = a.get("rel_ci_low") != null && truth >= ((Number) a.get("rel_ci_low")).doubleValue() && truth <= ((Number) a.get("rel_ci_high")).doubleValue();
                am.put("ci_covers_truth", cov);
                ciTotal++;
                if (cov) covered++;
                arms.add(am);
            }
            m.put("arms", arms);
            experiments.add(m);
        }
        eval.put("experiments", experiments);
        eval.put("recommendation_accuracy", total == 0 ? null : (double) correct / total);
        eval.put("recommendation_correct", correct);
        eval.put("recommendation_total", total);
        eval.put("ci_coverage", ciTotal == 0 ? null : (double) covered / ciTotal);
        eval.put("ci_covered", covered);
        eval.put("ci_total", ciTotal);
        checks.add(new Check("ANALYSIS_EVALUATION", "Engine recommendation matches expected (ground truth)", "analysis_run",
            correct == total ? "PASS" : "WARN", total - correct, 0L, correct + "/" + total + " experiments match"));
        checks.add(new Check("ANALYSIS_EVALUATION", "Confidence intervals cover the true ITT lift", "experiment_result",
            ciTotal == covered ? "PASS" : "WARN", ciTotal - covered, 0L, covered + "/" + ciTotal + " treatment arms covered (nominal 95%+)"));
        return eval;
    }

    // ================================================================== helpers
    private void zero(List<Check> out, String cat, String name, String target, String sql, Object... args) {
        out.add(check(cat, name, target, db.count(sql, args), null));
    }

    private static Check check(String cat, String name, String target, long violations, Long expected) {
        return new Check(cat, name, target, violations == 0 ? "PASS" : "FAIL", violations, expected, violations == 0 ? "OK" : violations + " violating rows");
    }

    private static String s(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? null : v.toString();
    }

    private static String trunc(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n - 3) + "...";
    }
}
