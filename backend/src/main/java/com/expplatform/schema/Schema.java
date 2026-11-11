package com.expplatform.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for every table: DDL, data dictionary, generic validation
 * (PK / FK / enum / pattern / required checks) and export ordering are all derived from here.
 */
public final class Schema {

    private Schema() {}

    public record Column(String name, String sqlType, boolean required, List<String> allowed, String unit,
                         boolean unique, String pattern, String fkTable, String fkColumn, String rule,
                         String description) {}

    public record Table(String name, String kind, String description, String primaryKey, String cardinality,
                        String lifecycle, List<Column> columns, List<String> extraKeys, List<String[]> compositeUniques) {
        public Column column(String n) {
            return columns.stream().filter(c -> c.name().equals(n)).findFirst().orElse(null);
        }
        public List<String> columnNames() {
            return columns.stream().map(Column::name).toList();
        }
    }

    // ------------------------------------------------------------------ builder helpers
    private static final class C {
        final String name, sqlType, desc;
        boolean req; List<String> allowed = List.of(); String unit; boolean unique; String pattern;
        String fkT, fkC; String rule;
        C(String name, String sqlType, String desc) { this.name = name; this.sqlType = sqlType; this.desc = desc; }
        C req() { req = true; return this; }
        C en(String... v) { allowed = Arrays.asList(v); return this; }
        C unit(String u) { unit = u; return this; }
        C uq() { unique = true; return this; }
        C pat(String p) { pattern = p; return this; }
        C fk(String t, String c) { fkT = t; fkC = c; return this; }
        C rule(String r) { rule = r; return this; }
        Column build() { return new Column(name, sqlType, req, allowed, unit, unique, pattern, fkT, fkC, rule, desc); }
    }

    private static C c(String name, String sqlType, String desc) { return new C(name, sqlType, desc); }

    private static Table t(String name, String kind, String desc, String pk, String cardinality, String lifecycle,
                           List<String> extra, List<String[]> uniques, C... cols) {
        List<Column> list = new ArrayList<>();
        for (C col : cols) list.add(col.build());
        return new Table(name, kind, desc, pk, cardinality, lifecycle, list, extra, uniques);
    }

    // ------------------------------------------------------------------ enums
    public static final String[] STATUSES = {"DRAFT", "DESIGNED", "RUNNING", "STOPPED", "ANALYZED", "DECIDED", "ARCHIVED", "CANCELLED"};
    public static final String[] EVENT_TYPES = {"CONVERSION", "SUBSCRIPTION", "TRIAL_ACTIVATION", "UNSUBSCRIBE", "SUPPORT_TICKET"};
    public static final String[] RECOMMENDATIONS = {"SHIP", "DO_NOT_SHIP", "INCONCLUSIVE", "BLOCKED_GUARDRAIL", "INVALID_SRM"};
    public static final String[] PLATFORMS = {"IOS", "ANDROID", "WEB"};
    public static final String[] COUNTRIES = {"US", "IN", "GB", "DE", "BR", "CA", "FR", "AU"};
    public static final String[] ISSUE_TYPES = {
            "MULTIPLE_VARIANT_ASSIGNMENT", "EXPOSURE_BEFORE_ASSIGNMENT", "OUTCOME_WINDOW_INCONSISTENT",
            "SAMPLE_RATIO_MISMATCH", "SRM_IGNORED", "METRIC_DEFINITION_CHANGED_AFTER_RESULT", "GUARDRAIL_REGRESSION",
            "NONCOMPLIANCE", "MISSING_TELEMETRY", "TELEMETRY_DROPPED", "CONVERSION_OUTSIDE_WINDOW",
            "EXPECTED_RECOMMENDATION", "TRUE_ITT_RELATIVE_LIFT", "SCENARIO"};

    private static final String EXP_PAT = "^EXP-2026-[0-9]{6}$";
    private static final String USR_PAT = "^USR-[0-9]{6}$";
    private static final String VAR_PAT = "^VAR-EXP-2026-[0-9]{6}-[A-Z]$";
    private static final String MDEF_PAT = "^MDEF-[A-Z_]+-V[0-9]+$";

    // ------------------------------------------------------------------ tables (dependency order)
    public static final List<Table> DATASET_TABLES = List.of(

        t("metric_definition", "MASTER (versioned)",
          "Versioned metric definitions. A change to a metric creates a NEW row (new version); rows are never updated. effective_to is derived as the next version's effective_from.",
          "metric_def_id",
          "metric_definition 1:N experiment (primary metric, pinned version); metric_definition 1:N guardrail_metric; 1:N experiment_result",
          "Versions: V1 -> V2 -> ...; a version is active from effective_from until the next version's effective_from.",
          List.of(), List.<String[]>of(new String[]{"metric_code", "version"}),
          c("metric_def_id", "VARCHAR(64)", "Primary key: MDEF-<METRIC_CODE>-V<version>").req().uq().pat(MDEF_PAT),
          c("metric_code", "VARCHAR(40)", "Stable business code of the metric across versions").req().pat("^[A-Z_]+$"),
          c("version", "INT", "Version number, starts at 1").req().rule(">= 1; (metric_code, version) unique"),
          c("name", "VARCHAR(120)", "Human readable name").req(),
          c("description", "VARCHAR(500)", "Exact definition used by the analysis engine").req(),
          c("metric_type", "VARCHAR(16)", "PROPORTION = share of users with >=1 event in window; MEAN = mean of per-user average value").req().en("PROPORTION", "MEAN"),
          c("source_entity", "VARCHAR(24)", "Table the metric is computed from").req().en("CONVERSION_EVENT", "EXPOSURE_EVENT"),
          c("event_type", "VARCHAR(24)", "conversion_event.event_type counted (PROPORTION metrics)").en(EVENT_TYPES).rule("required when source_entity = CONVERSION_EVENT"),
          c("value_field", "VARCHAR(32)", "Numeric field averaged (MEAN metrics)").en("page_load_ms").rule("required when metric_type = MEAN"),
          c("window_days", "INT", "Outcome window measured from assignment.assigned_at").req().unit("days").rule("1..30"),
          c("unit", "VARCHAR(16)", "Unit of the metric value").req().en("ratio", "ms"),
          c("desired_direction", "VARCHAR(10)", "Direction that is good for the business").req().en("INCREASE", "DECREASE"),
          c("effective_from", "DATETIME", "When this version became the official definition (UTC)").req().unit("UTC"),
          c("created_by", "VARCHAR(60)", "Actor who registered the version").req(),
          c("change_reason", "VARCHAR(300)", "Why the version was created")),

        t("experiment", "MASTER",
          "An experiment and its pre-declared analysis plan (hypothesis, primary metric, alpha, power, MDE, SRM threshold, population, correction).",
          "experiment_id",
          "experiment 1:N variant; experiment 1:N guardrail_metric; experiment N:M audience_member via experiment_eligibility and assignment; experiment 1:N analysis_run; 1:N experiment_decision; 1:N experiment_status_event",
          "DRAFT -> DESIGNED (plan locked) -> RUNNING -> STOPPED -> ANALYZED -> DECIDED -> ARCHIVED; DRAFT/DESIGNED/RUNNING -> CANCELLED. Every change is an experiment_status_event row.",
          List.of(), List.of(),
          c("experiment_id", "VARCHAR(20)", "Primary key EXP-2026-NNNNNN").req().uq().pat(EXP_PAT),
          c("name", "VARCHAR(120)", "Experiment name").req(),
          c("hypothesis", "VARCHAR(1000)", "Pre-registered hypothesis").req(),
          c("owner_team", "VARCHAR(20)", "Owning team").req().en("GROWTH", "MARKETING_OPS", "SALES_OPS", "PRODUCT", "SUPPORT_OPS", "PLATFORM"),
          c("requested_by", "VARCHAR(60)", "Actor that requested the experiment").req(),
          c("primary_metric_def_id", "VARCHAR(64)", "Pinned primary metric version (analysis plan)").req().fk("metric_definition", "metric_def_id"),
          c("randomization_unit", "VARCHAR(10)", "Unit of randomization").req().en("USER"),
          c("allocation_method", "VARCHAR(20)", "bucket = hash(seed, experiment_id, user_id) mod 10000").req().en("HASH_SEEDED"),
          c("randomization_seed", "BIGINT", "Fixed seed used for hashing").req(),
          c("start_at", "DATETIME", "Start of enrollment (UTC)").req().unit("UTC"),
          c("end_at", "DATETIME", "End of enrollment (UTC)").req().unit("UTC").rule("> start_at"),
          c("baseline_rate", "DOUBLE", "Planning assumption for control rate").req().unit("ratio").rule("0 < x < 1"),
          c("mde_relative", "DOUBLE", "Minimum detectable relative effect used for sizing").req().unit("ratio").rule("> 0"),
          c("alpha", "DOUBLE", "Two-sided significance level").req().rule("0 < alpha <= 0.2"),
          c("power", "DOUBLE", "Target power").req().rule("0.5 <= power < 1"),
          c("planned_sample_size", "INT", "Total users needed (all arms), computed from baseline/mde/alpha/power").req().unit("users"),
          c("srm_alpha", "DOUBLE", "p-value threshold for the sample-ratio-mismatch chi-square test").req(),
          c("analysis_population", "VARCHAR(16)", "Population analysed").req().en("ITT"),
          c("multiple_testing", "VARCHAR(16)", "Correction across treatment arms").req().en("BONFERRONI", "NONE"),
          c("analysis_plan_locked_at", "DATETIME", "When the plan was frozen (status -> DESIGNED)").unit("UTC").rule("<= start_at"),
          c("status", "VARCHAR(16)", "Current lifecycle state (mirror of latest status event)").req().en(STATUSES).rule("= latest experiment_status_event.to_status"),
          c("created_at", "DATETIME", "Creation time").req().unit("UTC")),

        t("variant", "MASTER",
          "Arms of an experiment. variant_key A is always control.",
          "variant_id", "experiment 1:N variant (2..5 per experiment, exactly one control)", "Immutable once the plan is locked.",
          List.of(), List.<String[]>of(new String[]{"experiment_id", "variant_key"}),
          c("variant_id", "VARCHAR(32)", "Primary key VAR-<experiment-id>-<key>").req().uq().pat(VAR_PAT),
          c("experiment_id", "VARCHAR(20)", "Parent experiment").req().fk("experiment", "experiment_id"),
          c("variant_key", "CHAR(1)", "Arm letter").req().pat("^[A-E]$"),
          c("name", "VARCHAR(80)", "Arm name").req(),
          c("description", "VARCHAR(300)", "What the arm changes"),
          c("is_control", "BOOLEAN", "True for the control arm").req().rule("exactly one per experiment"),
          c("planned_allocation_pct", "DOUBLE", "Planned traffic share").req().unit("percent").rule("sum per experiment = 100")),

        t("guardrail_metric", "BRIDGE",
          "Bridge between experiment and metric_definition (N:M) holding the guardrail tolerance.",
          "guardrail_id", "experiment N:M metric_definition", "Fixed once the plan is locked.",
          List.of(), List.<String[]>of(new String[]{"experiment_id", "metric_def_id"}),
          c("guardrail_id", "VARCHAR(40)", "Primary key GRD-<experiment-id>-NN").req().uq().pat("^GRD-EXP-2026-[0-9]{6}-[0-9]{2}$"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("metric_def_id", "VARCHAR(64)", "Guardrail metric version").req().fk("metric_definition", "metric_def_id"),
          c("max_relative_degradation", "DOUBLE", "Tolerated relative degradation vs control").req().unit("ratio").rule("0 < x <= 1"),
          c("severity", "VARCHAR(12)", "BLOCKING guardrails block shipping on FAIL").req().en("BLOCKING", "ADVISORY")),

        t("audience_member", "MASTER",
          "Synthetic customers/prospects. Pre-period covariates are used for the randomization balance check.",
          "user_id", "audience_member N:M experiment via experiment_eligibility / assignment", "Static during the study window.",
          List.of(), List.of(),
          c("user_id", "VARCHAR(16)", "Primary key USR-NNNNNN").req().uq().pat(USR_PAT),
          c("user_type", "VARCHAR(12)", "Customer or prospect").req().en("CUSTOMER", "PROSPECT"),
          c("country", "CHAR(2)", "ISO-3166 alpha-2").req().en(COUNTRIES),
          c("platform", "VARCHAR(10)", "Primary platform").req().en(PLATFORMS),
          c("acquisition_channel", "VARCHAR(20)", "Acquisition channel").req().en("ORGANIC", "PAID_SEARCH", "EMAIL", "SOCIAL", "REFERRAL", "SALES_OUTREACH"),
          c("signup_date", "DATE", "Signup date").req().rule("< experiment start"),
          c("pre_period_sessions", "INT", "Sessions in the 28 days before enrollment").req().unit("sessions").rule(">= 0"),
          c("pre_period_orders", "INT", "Orders in the 28 days before enrollment").req().unit("orders").rule("0 <= x <= pre_period_sessions"),
          c("marketing_consent", "BOOLEAN", "Opted into marketing email").req(),
          c("created_at", "DATETIME", "Record creation").req().unit("UTC")),

        t("experiment_eligibility", "BRIDGE",
          "Eligible audience per experiment (N:M experiment<->audience_member). Eligible users without an assignment reveal dropped assignments.",
          "eligibility_id", "experiment N:M audience_member", "ELIGIBLE at audience snapshot time.",
          List.of(), List.<String[]>of(new String[]{"experiment_id", "user_id"}),
          c("eligibility_id", "VARCHAR(48)", "Primary key ELG-<experiment-id>-<user-id>").req().uq().pat("^ELG-EXP-2026-[0-9]{6}-USR-[0-9]{6}$"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("user_id", "VARCHAR(16)", "User").req().fk("audience_member", "user_id"),
          c("eligible_at", "DATETIME", "Audience snapshot time").req().unit("UTC"),
          c("eligibility_rule", "VARCHAR(120)", "Rule that made the user eligible").req(),
          c("eligibility_status", "VARCHAR(12)", "Eligibility state").req().en("ELIGIBLE")),

        t("assignment", "BRIDGE / EVENT",
          "Randomized assignment of a user to a variant. Append-only: a second assignment for the same user is a new row (labelled anomaly), never an update.",
          "assignment_id", "experiment 1:N assignment; audience_member 1:N assignment; variant 1:N assignment (expected 1 per experiment-user)",
          "Immutable.",
          List.of("KEY ix_asg_exp_user (experiment_id, user_id)"), List.of(),
          c("assignment_id", "VARCHAR(56)", "Primary key ASG-<experiment-id>-<user-id>[-n]").req().uq().pat("^ASG-EXP-2026-[0-9]{6}-USR-[0-9]{6}(-[0-9]+)?$"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("user_id", "VARCHAR(16)", "User").req().fk("audience_member", "user_id"),
          c("variant_id", "VARCHAR(32)", "Assigned variant").req().fk("variant", "variant_id"),
          c("bucket", "INT", "hash(seed, experiment_id, user_id) mod 10000").req().rule("0..9999"),
          c("assigned_at", "DATETIME", "Assignment time").req().unit("UTC").rule("within [start_at, end_at]")),

        t("exposure_event", "EVENT",
          "Logged renderings of a variant. variant_id is what was actually shown (differs from assignment on noncompliance).",
          "exposure_id", "assignment 1:N exposure_event (0..N)", "Append-only.",
          List.of("KEY ix_expo_exp_user (experiment_id, user_id)"), List.of(),
          c("exposure_id", "VARCHAR(24)", "Primary key EXPOS-2026-NNNNNN").req().uq().pat("^EXPOS-2026-[0-9]{6,}$"),
          c("assignment_id", "VARCHAR(56)", "Assignment the exposure belongs to").req().fk("assignment", "assignment_id"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("user_id", "VARCHAR(16)", "User").req().fk("audience_member", "user_id"),
          c("variant_id", "VARCHAR(32)", "Variant actually rendered").req().fk("variant", "variant_id"),
          c("exposed_at", "DATETIME", "Render time").req().unit("UTC").rule(">= assignment.assigned_at (violations are labelled anomalies)"),
          c("surface", "VARCHAR(24)", "Product surface").req().en("CHECKOUT", "HOMEPAGE", "PRICING_PAGE", "EMAIL", "ONBOARDING", "PRODUCT_PAGE", "SEARCH", "APP_HOME"),
          c("platform", "VARCHAR(10)", "Platform").req().en(PLATFORMS),
          c("page_load_ms", "INT", "Client page load time").unit("ms").rule("NULL iff telemetry_status = PARTIAL"),
          c("telemetry_status", "VARCHAR(10)", "Completeness of the telemetry payload").req().en("COMPLETE", "PARTIAL")),

        t("conversion_event", "EVENT",
          "Outcome events (conversions, subscriptions, activations, unsubscribes, support tickets). reported_in_window is the source system's claim; analysis recomputes the window.",
          "conversion_id", "assignment 1:N conversion_event (0..N)", "Append-only; ingested_at >= occurred_at models delayed arrival.",
          List.of("KEY ix_conv_exp_user (experiment_id, user_id)"), List.of(),
          c("conversion_id", "VARCHAR(24)", "Primary key CONV-2026-NNNNNN").req().uq().pat("^CONV-2026-[0-9]{6,}$"),
          c("assignment_id", "VARCHAR(56)", "Attributed assignment").req().fk("assignment", "assignment_id"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("user_id", "VARCHAR(16)", "User").req().fk("audience_member", "user_id"),
          c("event_type", "VARCHAR(24)", "Outcome type").req().en(EVENT_TYPES),
          c("occurred_at", "DATETIME", "When it happened").req().unit("UTC").rule(">= assignment.assigned_at"),
          c("ingested_at", "DATETIME", "When the warehouse received it").req().unit("UTC").rule(">= occurred_at"),
          c("revenue_amount", "DECIMAL(10,2)", "Revenue").unit("USD").rule("> 0 for CONVERSION/SUBSCRIPTION, NULL otherwise"),
          c("currency", "CHAR(3)", "Currency").en("USD").rule("NOT NULL iff revenue_amount NOT NULL"),
          c("source_system", "VARCHAR(20)", "Producing system").req().en("WEB_CHECKOUT", "MOBILE_APP", "EMAIL_PLATFORM", "SUPPORT_DESK", "CRM"),
          c("reported_in_window", "BOOLEAN", "Source system claim that the event is inside the outcome window").req()),

        t("experiment_status_event", "EVENT (history)",
          "Every lifecycle transition of an experiment. Never updated or deleted.",
          "status_event_id", "experiment 1:N experiment_status_event", "Chain: from_status = previous to_status.",
          List.of(), List.of(),
          c("status_event_id", "VARCHAR(20)", "Primary key SEV-2026-NNNNNN").req().uq().pat("^SEV-2026-[0-9]{6}$"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("from_status", "VARCHAR(16)", "Previous state (NULL for creation)").en(STATUSES),
          c("to_status", "VARCHAR(16)", "New state").req().en(STATUSES),
          c("changed_at", "DATETIME", "Transition time").req().unit("UTC"),
          c("changed_by", "VARCHAR(60)", "Actor").req(),
          c("reason", "VARCHAR(300)", "Reason")),

        t("analysis_run", "DERIVED",
          "One execution of the pre-declared analysis plan. Re-analysis appends a new run; old runs are kept.",
          "run_id", "experiment 1:N analysis_run; analysis_run 1:N experiment_result", "Immutable.",
          List.of(), List.of(),
          c("run_id", "VARCHAR(20)", "Primary key RUN-2026-NNNNNN").req().uq().pat("^RUN-2026-[0-9]{6}$"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("primary_metric_def_id", "VARCHAR(64)", "Metric version used").req().fk("metric_definition", "metric_def_id"),
          c("computed_at", "DATETIME", "Run time").req().unit("UTC"),
          c("data_cutoff_at", "DATETIME", "Events ingested after this are ignored").req().unit("UTC"),
          c("triggered_by", "VARCHAR(60)", "Actor").req(),
          c("analysis_population", "VARCHAR(16)", "Population").req().en("ITT"),
          c("n_assigned_users", "INT", "Distinct assigned users").req().unit("users"),
          c("n_excluded_multi_assignment", "INT", "Users excluded because assigned to >1 variant").req().unit("users"),
          c("n_analyzed_users", "INT", "= n_assigned_users - n_excluded_multi_assignment").req().unit("users"),
          c("n_exposure_before_assignment", "INT", "Exposures timestamped before their assignment").req().unit("events"),
          c("n_noncompliant_users", "INT", "Analysed users who saw a variant other than the assigned one").req().unit("users"),
          c("n_never_exposed_users", "INT", "Analysed users with no logged exposure").req().unit("users"),
          c("n_missing_telemetry_exposures", "INT", "Exposures with PARTIAL telemetry").req().unit("events"),
          c("n_out_of_window_reported", "INT", "Primary events reported in-window by source but outside the recomputed window").req().unit("events"),
          c("srm_chi2", "DOUBLE", "Chi-square statistic of observed vs planned allocation").req(),
          c("srm_df", "INT", "Degrees of freedom (arms - 1)").req(),
          c("srm_p_value", "DOUBLE", "SRM p-value").req(),
          c("srm_status", "VARCHAR(8)", "FAIL if srm_p_value < experiment.srm_alpha").req().en("PASS", "FAIL"),
          c("balance_platform_p", "DOUBLE", "Chi-square independence p-value platform x variant"),
          c("balance_sessions_smd", "DOUBLE", "Max |standardized mean difference| of pre_period_sessions vs control"),
          c("balance_status", "VARCHAR(8)", "WARN if platform p < 0.01 or |SMD| > 0.1").req().en("PASS", "WARN"),
          c("recommendation", "VARCHAR(20)", "Engine recommendation").req().en(RECOMMENDATIONS),
          c("recommended_variant_id", "VARCHAR(32)", "Arm recommended for SHIP").fk("variant", "variant_id"),
          c("metric_version_note", "VARCHAR(300)", "Note when a newer metric version exists"),
          c("notes", "VARCHAR(1000)", "Decision trace")),

        t("experiment_result", "DERIVED",
          "Per variant x metric estimates for a run. Calculated from source records, never generated independently.",
          "result_id", "analysis_run 1:N experiment_result", "Immutable.",
          List.of("KEY ix_res_run (run_id)"), List.of(),
          c("result_id", "VARCHAR(20)", "Primary key RES-2026-NNNNNN").req().uq().pat("^RES-2026-[0-9]{6}$"),
          c("run_id", "VARCHAR(20)", "Analysis run").req().fk("analysis_run", "run_id"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("variant_id", "VARCHAR(32)", "Variant").req().fk("variant", "variant_id"),
          c("metric_def_id", "VARCHAR(64)", "Metric version").req().fk("metric_definition", "metric_def_id"),
          c("guardrail_id", "VARCHAR(40)", "Guardrail (GUARDRAIL rows)").fk("guardrail_metric", "guardrail_id"),
          c("metric_role", "VARCHAR(10)", "Role").req().en("PRIMARY", "GUARDRAIL"),
          c("n_users", "INT", "Denominator").req().unit("users"),
          c("numerator", "DOUBLE", "Converted users (PROPORTION) or sum of per-user means (MEAN)").req(),
          c("metric_value", "DOUBLE", "= numerator / n_users").req(),
          c("std_dev", "DOUBLE", "Sample std-dev of per-user values"),
          c("control_value", "DOUBLE", "Control metric_value (treatment rows)"),
          c("abs_diff", "DOUBLE", "= metric_value - control_value"),
          c("rel_diff", "DOUBLE", "= abs_diff / control_value").unit("ratio"),
          c("ci_low", "DOUBLE", "Lower CI bound of abs_diff"),
          c("ci_high", "DOUBLE", "Upper CI bound of abs_diff"),
          c("rel_ci_low", "DOUBLE", "ci_low / control_value").unit("ratio"),
          c("rel_ci_high", "DOUBLE", "ci_high / control_value").unit("ratio"),
          c("p_value", "DOUBLE", "Two-sided p-value"),
          c("alpha_applied", "DOUBLE", "Alpha after correction"),
          c("is_significant", "BOOLEAN", "p_value < alpha_applied"),
          c("guardrail_status", "VARCHAR(8)", "Guardrail verdict").en("PASS", "WARN", "FAIL"),
          c("arm_decision", "VARCHAR(20)", "Per-arm decision (primary treatment rows)").en(RECOMMENDATIONS)),

        t("experiment_decision", "EVENT",
          "Human decision taken on a run. Append-only; a reversal is a new decision row.",
          "decision_id", "experiment 1:N experiment_decision; analysis_run 1:N experiment_decision", "Immutable.",
          List.of(), List.of(),
          c("decision_id", "VARCHAR(20)", "Primary key DEC-2026-NNNNNN").req().uq().pat("^DEC-2026-[0-9]{6}$"),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("run_id", "VARCHAR(20)", "Run the decision relied on").req().fk("analysis_run", "run_id"),
          c("decision", "VARCHAR(16)", "Decision").req().en("SHIP", "DO_NOT_SHIP", "ITERATE", "ROLLBACK"),
          c("chosen_variant_id", "VARCHAR(32)", "Variant shipped").fk("variant", "variant_id"),
          c("decided_by", "VARCHAR(60)", "Actor").req(),
          c("decided_at", "DATETIME", "Decision time").req().unit("UTC").rule(">= analysis_run.computed_at"),
          c("srm_acknowledged", "BOOLEAN", "Decision maker acknowledged an SRM failure").req(),
          c("guardrails_acknowledged", "BOOLEAN", "Decision maker acknowledged guardrail failures").req(),
          c("rationale", "VARCHAR(1000)", "Rationale"))
    );

    public static final Table GROUND_TRUTH = t("ground_truth", "GROUND TRUTH (separate)",
          "Labels produced by the generator from its documented rules. Never read by the analysis engine or the detectors; only used for evaluation.",
          "gt_id", "Logical reference to any entity via (entity_type, entity_id)", "Write-once.",
          List.of("KEY ix_gt_type (issue_type)"), List.of(),
          c("gt_id", "VARCHAR(20)", "Primary key GT-2026-NNNNNN").req().uq().pat("^GT-2026-[0-9]{6}$"),
          c("scope", "VARCHAR(12)", "ROW-level anomaly or EXPERIMENT-level expectation").req().en("ROW", "EXPERIMENT"),
          c("issue_type", "VARCHAR(48)", "Label").req().en(ISSUE_TYPES),
          c("entity_type", "VARCHAR(24)", "Entity referenced").req().en("EXPERIMENT", "VARIANT", "ASSIGNMENT", "EXPOSURE_EVENT", "CONVERSION_EVENT", "ANALYSIS_RUN", "EXPERIMENT_DECISION"),
          c("entity_id", "VARCHAR(64)", "Entity id").req(),
          c("experiment_id", "VARCHAR(20)", "Experiment").req().fk("experiment", "experiment_id"),
          c("expected_value", "VARCHAR(120)", "Expected value / decision"),
          c("notes", "VARCHAR(500)", "Generation rule that produced the label"));

    /** Operational tables (not part of the exported dataset). */
    public static final List<String> OPERATIONAL_DDL = List.of(
        "CREATE TABLE IF NOT EXISTS generation_run (gen_run_id VARCHAR(20) NOT NULL, seed BIGINT NOT NULL, users INT NOT NULL, experiments INT NOT NULL, started_at DATETIME NOT NULL, finished_at DATETIME, duration_ms BIGINT, status VARCHAR(12) NOT NULL, row_counts TEXT, triggered_by VARCHAR(60), PRIMARY KEY (gen_run_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "CREATE TABLE IF NOT EXISTS validation_run (validation_run_id VARCHAR(20) NOT NULL, gen_run_id VARCHAR(20), run_at DATETIME NOT NULL, passed INT NOT NULL, failed INT NOT NULL, warnings INT NOT NULL, evaluation_json LONGTEXT, PRIMARY KEY (validation_run_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "CREATE TABLE IF NOT EXISTS validation_check (check_id VARCHAR(24) NOT NULL, validation_run_id VARCHAR(20) NOT NULL, category VARCHAR(30) NOT NULL, check_name VARCHAR(200) NOT NULL, target VARCHAR(80), status VARCHAR(10) NOT NULL, violations INT NOT NULL, expected INT, details VARCHAR(1000), PRIMARY KEY (check_id), KEY ix_vc_run (validation_run_id), FOREIGN KEY (validation_run_id) REFERENCES validation_run(validation_run_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
        "CREATE TABLE IF NOT EXISTS detected_issue (issue_id VARCHAR(24) NOT NULL, validation_run_id VARCHAR(20) NOT NULL, issue_type VARCHAR(48) NOT NULL, entity_type VARCHAR(24) NOT NULL, entity_id VARCHAR(64) NOT NULL, experiment_id VARCHAR(20), details VARCHAR(500), PRIMARY KEY (issue_id), KEY ix_di_run_type (validation_run_id, issue_type), KEY ix_di_exp (experiment_id), FOREIGN KEY (validation_run_id) REFERENCES validation_run(validation_run_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );

    public static final List<String> OPERATIONAL_TABLES = List.of("detected_issue", "validation_check", "validation_run", "generation_run");

    public static List<Table> allTables() {
        List<Table> all = new ArrayList<>(DATASET_TABLES);
        all.add(GROUND_TRUTH);
        return all;
    }

    public static Table table(String name) {
        return allTables().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }

    public static Map<String, Table> byName() {
        Map<String, Table> m = new LinkedHashMap<>();
        for (Table t : allTables()) m.put(t.name(), t);
        return m;
    }

    /** CREATE TABLE statement generated from the registry. */
    public static String ddl(Table t) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(t.name()).append(" (\n");
        List<String> parts = new ArrayList<>();
        for (Column c : t.columns()) {
            parts.add("  " + c.name() + " " + c.sqlType() + (c.required() ? " NOT NULL" : " NULL"));
        }
        parts.add("  PRIMARY KEY (" + t.primaryKey() + ")");
        for (String[] u : t.compositeUniques()) {
            parts.add("  UNIQUE KEY uq_" + t.name() + "_" + String.join("_", u) + " (" + String.join(", ", u) + ")");
        }
        for (Column c : t.columns()) {
            if (c.unique() && !c.name().equals(t.primaryKey())) parts.add("  UNIQUE KEY uq_" + t.name() + "_" + c.name() + " (" + c.name() + ")");
        }
        parts.addAll(t.extraKeys().stream().map(k -> "  " + k).toList());
        for (Column c : t.columns()) {
            if (c.fkTable() != null) {
                parts.add("  CONSTRAINT fk_" + t.name() + "_" + c.name() + " FOREIGN KEY (" + c.name() + ") REFERENCES " + c.fkTable() + "(" + c.fkColumn() + ")");
            }
        }
        sb.append(String.join(",\n", parts)).append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        return sb.toString();
    }
}
