# Data Dictionary

Generated from `backend/src/main/java/com/expplatform/schema/Schema.java` (the same registry creates the DDL and drives validation).

Timestamps are UTC `DATETIME`. IDs are immutable and zero-padded.

| Table | Kind | Primary key | Cardinality |
|---|---|---|---|
| [metric_definition](#metric_definition) | MASTER (versioned) | `metric_def_id` | metric_definition 1:N experiment (primary metric, pinned version); metric_definition 1:N guardrail_metric; 1:N experiment_result |
| [experiment](#experiment) | MASTER | `experiment_id` | experiment 1:N variant; experiment 1:N guardrail_metric; experiment N:M audience_member via experiment_eligibility and assignment; experiment 1:N analysis_run; 1:N experiment_decision; 1:N experiment_status_event |
| [variant](#variant) | MASTER | `variant_id` | experiment 1:N variant (2..5 per experiment, exactly one control) |
| [guardrail_metric](#guardrail_metric) | BRIDGE | `guardrail_id` | experiment N:M metric_definition |
| [audience_member](#audience_member) | MASTER | `user_id` | audience_member N:M experiment via experiment_eligibility / assignment |
| [experiment_eligibility](#experiment_eligibility) | BRIDGE | `eligibility_id` | experiment N:M audience_member |
| [assignment](#assignment) | BRIDGE / EVENT | `assignment_id` | experiment 1:N assignment; audience_member 1:N assignment; variant 1:N assignment (expected 1 per experiment-user) |
| [exposure_event](#exposure_event) | EVENT | `exposure_id` | assignment 1:N exposure_event (0..N) |
| [conversion_event](#conversion_event) | EVENT | `conversion_id` | assignment 1:N conversion_event (0..N) |
| [experiment_status_event](#experiment_status_event) | EVENT (history) | `status_event_id` | experiment 1:N experiment_status_event |
| [analysis_run](#analysis_run) | DERIVED | `run_id` | experiment 1:N analysis_run; analysis_run 1:N experiment_result |
| [experiment_result](#experiment_result) | DERIVED | `result_id` | analysis_run 1:N experiment_result |
| [experiment_decision](#experiment_decision) | EVENT | `decision_id` | experiment 1:N experiment_decision; analysis_run 1:N experiment_decision |
| [ground_truth](#ground_truth) | GROUND TRUTH (separate) | `gt_id` | Logical reference to any entity via (entity_type, entity_id) |

## metric_definition

Versioned metric definitions. A change to a metric creates a NEW row (new version); rows are never updated. effective_to is derived as the next version's effective_from.

- **Kind:** MASTER (versioned)
- **Primary key:** `metric_def_id`
- **Cardinality:** metric_definition 1:N experiment (primary metric, pinned version); metric_definition 1:N guardrail_metric; 1:N experiment_result
- **Lifecycle / effective dates:** Versions: V1 -> V2 -> ...; a version is active from effective_from until the next version's effective_from.
- **Composite unique:** `(metric_code, version)` 

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `metric_def_id` | VARCHAR(64) | yes |  |  | yes | `^MDEF-[A-Z_]+-V[0-9]+$` |  | Primary key: MDEF-<METRIC_CODE>-V<version> |
| `metric_code` | VARCHAR(40) | yes |  |  |  | `^[A-Z_]+$` |  | Stable business code of the metric across versions |
| `version` | INT | yes |  |  |  | >= 1; (metric_code, version) unique |  | Version number, starts at 1 |
| `name` | VARCHAR(120) | yes |  |  |  |  |  | Human readable name |
| `description` | VARCHAR(500) | yes |  |  |  |  |  | Exact definition used by the analysis engine |
| `metric_type` | VARCHAR(16) | yes | PROPORTION, MEAN |  |  |  |  | PROPORTION = share of users with >=1 event in window; MEAN = mean of per-user average value |
| `source_entity` | VARCHAR(24) | yes | CONVERSION_EVENT, EXPOSURE_EVENT |  |  |  |  | Table the metric is computed from |
| `event_type` | VARCHAR(24) | no | CONVERSION, SUBSCRIPTION, TRIAL_ACTIVATION, UNSUBSCRIBE, SUPPORT_TICKET |  |  | required when source_entity = CONVERSION_EVENT |  | conversion_event.event_type counted (PROPORTION metrics) |
| `value_field` | VARCHAR(32) | no | page_load_ms |  |  | required when metric_type = MEAN |  | Numeric field averaged (MEAN metrics) |
| `window_days` | INT | yes |  | days |  | 1..30 |  | Outcome window measured from assignment.assigned_at |
| `unit` | VARCHAR(16) | yes | ratio, ms |  |  |  |  | Unit of the metric value |
| `desired_direction` | VARCHAR(10) | yes | INCREASE, DECREASE |  |  |  |  | Direction that is good for the business |
| `effective_from` | DATETIME | yes |  | UTC |  |  |  | When this version became the official definition (UTC) |
| `created_by` | VARCHAR(60) | yes |  |  |  |  |  | Actor who registered the version |
| `change_reason` | VARCHAR(300) | no |  |  |  |  |  | Why the version was created |

## experiment

An experiment and its pre-declared analysis plan (hypothesis, primary metric, alpha, power, MDE, SRM threshold, population, correction).

- **Kind:** MASTER
- **Primary key:** `experiment_id`
- **Cardinality:** experiment 1:N variant; experiment 1:N guardrail_metric; experiment N:M audience_member via experiment_eligibility and assignment; experiment 1:N analysis_run; 1:N experiment_decision; 1:N experiment_status_event
- **Lifecycle / effective dates:** DRAFT -> DESIGNED (plan locked) -> RUNNING -> STOPPED -> ANALYZED -> DECIDED -> ARCHIVED; DRAFT/DESIGNED/RUNNING -> CANCELLED. Every change is an experiment_status_event row.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `experiment_id` | VARCHAR(20) | yes |  |  | yes | `^EXP-2026-[0-9]{6}$` |  | Primary key EXP-2026-NNNNNN |
| `name` | VARCHAR(120) | yes |  |  |  |  |  | Experiment name |
| `hypothesis` | VARCHAR(1000) | yes |  |  |  |  |  | Pre-registered hypothesis |
| `owner_team` | VARCHAR(20) | yes | GROWTH, MARKETING_OPS, SALES_OPS, PRODUCT, SUPPORT_OPS, PLATFORM |  |  |  |  | Owning team |
| `requested_by` | VARCHAR(60) | yes |  |  |  |  |  | Actor that requested the experiment |
| `primary_metric_def_id` | VARCHAR(64) | yes |  |  |  |  | metric_definition.metric_def_id | Pinned primary metric version (analysis plan) |
| `randomization_unit` | VARCHAR(10) | yes | USER |  |  |  |  | Unit of randomization |
| `allocation_method` | VARCHAR(20) | yes | HASH_SEEDED |  |  |  |  | bucket = hash(seed, experiment_id, user_id) mod 10000 |
| `randomization_seed` | BIGINT | yes |  |  |  |  |  | Fixed seed used for hashing |
| `start_at` | DATETIME | yes |  | UTC |  |  |  | Start of enrollment (UTC) |
| `end_at` | DATETIME | yes |  | UTC |  | > start_at |  | End of enrollment (UTC) |
| `baseline_rate` | DOUBLE | yes |  | ratio |  | 0 < x < 1 |  | Planning assumption for control rate |
| `mde_relative` | DOUBLE | yes |  | ratio |  | > 0 |  | Minimum detectable relative effect used for sizing |
| `alpha` | DOUBLE | yes |  |  |  | 0 < alpha <= 0.2 |  | Two-sided significance level |
| `power` | DOUBLE | yes |  |  |  | 0.5 <= power < 1 |  | Target power |
| `planned_sample_size` | INT | yes |  | users |  |  |  | Total users needed (all arms), computed from baseline/mde/alpha/power |
| `srm_alpha` | DOUBLE | yes |  |  |  |  |  | p-value threshold for the sample-ratio-mismatch chi-square test |
| `analysis_population` | VARCHAR(16) | yes | ITT |  |  |  |  | Population analysed |
| `multiple_testing` | VARCHAR(16) | yes | BONFERRONI, NONE |  |  |  |  | Correction across treatment arms |
| `analysis_plan_locked_at` | DATETIME | no |  | UTC |  | <= start_at |  | When the plan was frozen (status -> DESIGNED) |
| `status` | VARCHAR(16) | yes | DRAFT, DESIGNED, RUNNING, STOPPED, ANALYZED, DECIDED, ARCHIVED, CANCELLED |  |  | = latest experiment_status_event.to_status |  | Current lifecycle state (mirror of latest status event) |
| `created_at` | DATETIME | yes |  | UTC |  |  |  | Creation time |

## variant

Arms of an experiment. variant_key A is always control.

- **Kind:** MASTER
- **Primary key:** `variant_id`
- **Cardinality:** experiment 1:N variant (2..5 per experiment, exactly one control)
- **Lifecycle / effective dates:** Immutable once the plan is locked.
- **Composite unique:** `(experiment_id, variant_key)` 

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `variant_id` | VARCHAR(32) | yes |  |  | yes | `^VAR-EXP-2026-[0-9]{6}-[A-Z]$` |  | Primary key VAR-<experiment-id>-<key> |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Parent experiment |
| `variant_key` | CHAR(1) | yes |  |  |  | `^[A-E]$` |  | Arm letter |
| `name` | VARCHAR(80) | yes |  |  |  |  |  | Arm name |
| `description` | VARCHAR(300) | no |  |  |  |  |  | What the arm changes |
| `is_control` | BOOLEAN | yes |  |  |  | exactly one per experiment |  | True for the control arm |
| `planned_allocation_pct` | DOUBLE | yes |  | percent |  | sum per experiment = 100 |  | Planned traffic share |

## guardrail_metric

Bridge between experiment and metric_definition (N:M) holding the guardrail tolerance.

- **Kind:** BRIDGE
- **Primary key:** `guardrail_id`
- **Cardinality:** experiment N:M metric_definition
- **Lifecycle / effective dates:** Fixed once the plan is locked.
- **Composite unique:** `(experiment_id, metric_def_id)` 

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `guardrail_id` | VARCHAR(40) | yes |  |  | yes | `^GRD-EXP-2026-[0-9]{6}-[0-9]{2}$` |  | Primary key GRD-<experiment-id>-NN |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `metric_def_id` | VARCHAR(64) | yes |  |  |  |  | metric_definition.metric_def_id | Guardrail metric version |
| `max_relative_degradation` | DOUBLE | yes |  | ratio |  | 0 < x <= 1 |  | Tolerated relative degradation vs control |
| `severity` | VARCHAR(12) | yes | BLOCKING, ADVISORY |  |  |  |  | BLOCKING guardrails block shipping on FAIL |

## audience_member

Synthetic customers/prospects. Pre-period covariates are used for the randomization balance check.

- **Kind:** MASTER
- **Primary key:** `user_id`
- **Cardinality:** audience_member N:M experiment via experiment_eligibility / assignment
- **Lifecycle / effective dates:** Static during the study window.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `user_id` | VARCHAR(16) | yes |  |  | yes | `^USR-[0-9]{6}$` |  | Primary key USR-NNNNNN |
| `user_type` | VARCHAR(12) | yes | CUSTOMER, PROSPECT |  |  |  |  | Customer or prospect |
| `country` | CHAR(2) | yes | US, IN, GB, DE, BR, CA, FR, AU |  |  |  |  | ISO-3166 alpha-2 |
| `platform` | VARCHAR(10) | yes | IOS, ANDROID, WEB |  |  |  |  | Primary platform |
| `acquisition_channel` | VARCHAR(20) | yes | ORGANIC, PAID_SEARCH, EMAIL, SOCIAL, REFERRAL, SALES_OUTREACH |  |  |  |  | Acquisition channel |
| `signup_date` | DATE | yes |  |  |  | < experiment start |  | Signup date |
| `pre_period_sessions` | INT | yes |  | sessions |  | >= 0 |  | Sessions in the 28 days before enrollment |
| `pre_period_orders` | INT | yes |  | orders |  | 0 <= x <= pre_period_sessions |  | Orders in the 28 days before enrollment |
| `marketing_consent` | BOOLEAN | yes |  |  |  |  |  | Opted into marketing email |
| `created_at` | DATETIME | yes |  | UTC |  |  |  | Record creation |

## experiment_eligibility

Eligible audience per experiment (N:M experiment<->audience_member). Eligible users without an assignment reveal dropped assignments.

- **Kind:** BRIDGE
- **Primary key:** `eligibility_id`
- **Cardinality:** experiment N:M audience_member
- **Lifecycle / effective dates:** ELIGIBLE at audience snapshot time.
- **Composite unique:** `(experiment_id, user_id)` 

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `eligibility_id` | VARCHAR(48) | yes |  |  | yes | `^ELG-EXP-2026-[0-9]{6}-USR-[0-9]{6}$` |  | Primary key ELG-<experiment-id>-<user-id> |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `user_id` | VARCHAR(16) | yes |  |  |  |  | audience_member.user_id | User |
| `eligible_at` | DATETIME | yes |  | UTC |  |  |  | Audience snapshot time |
| `eligibility_rule` | VARCHAR(120) | yes |  |  |  |  |  | Rule that made the user eligible |
| `eligibility_status` | VARCHAR(12) | yes | ELIGIBLE |  |  |  |  | Eligibility state |

## assignment

Randomized assignment of a user to a variant. Append-only: a second assignment for the same user is a new row (labelled anomaly), never an update.

- **Kind:** BRIDGE / EVENT
- **Primary key:** `assignment_id`
- **Cardinality:** experiment 1:N assignment; audience_member 1:N assignment; variant 1:N assignment (expected 1 per experiment-user)
- **Lifecycle / effective dates:** Immutable.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `assignment_id` | VARCHAR(56) | yes |  |  | yes | `^ASG-EXP-2026-[0-9]{6}-USR-[0-9]{6}(-[0-9]+)?$` |  | Primary key ASG-<experiment-id>-<user-id>[-n] |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `user_id` | VARCHAR(16) | yes |  |  |  |  | audience_member.user_id | User |
| `variant_id` | VARCHAR(32) | yes |  |  |  |  | variant.variant_id | Assigned variant |
| `bucket` | INT | yes |  |  |  | 0..9999 |  | hash(seed, experiment_id, user_id) mod 10000 |
| `assigned_at` | DATETIME | yes |  | UTC |  | within [start_at, end_at] |  | Assignment time |

## exposure_event

Logged renderings of a variant. variant_id is what was actually shown (differs from assignment on noncompliance).

- **Kind:** EVENT
- **Primary key:** `exposure_id`
- **Cardinality:** assignment 1:N exposure_event (0..N)
- **Lifecycle / effective dates:** Append-only.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `exposure_id` | VARCHAR(24) | yes |  |  | yes | `^EXPOS-2026-[0-9]{6,}$` |  | Primary key EXPOS-2026-NNNNNN |
| `assignment_id` | VARCHAR(56) | yes |  |  |  |  | assignment.assignment_id | Assignment the exposure belongs to |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `user_id` | VARCHAR(16) | yes |  |  |  |  | audience_member.user_id | User |
| `variant_id` | VARCHAR(32) | yes |  |  |  |  | variant.variant_id | Variant actually rendered |
| `exposed_at` | DATETIME | yes |  | UTC |  | >= assignment.assigned_at (violations are labelled anomalies) |  | Render time |
| `surface` | VARCHAR(24) | yes | CHECKOUT, HOMEPAGE, PRICING_PAGE, EMAIL, ONBOARDING, PRODUCT_PAGE, SEARCH, APP_HOME |  |  |  |  | Product surface |
| `platform` | VARCHAR(10) | yes | IOS, ANDROID, WEB |  |  |  |  | Platform |
| `page_load_ms` | INT | no |  | ms |  | NULL iff telemetry_status = PARTIAL |  | Client page load time |
| `telemetry_status` | VARCHAR(10) | yes | COMPLETE, PARTIAL |  |  |  |  | Completeness of the telemetry payload |

## conversion_event

Outcome events (conversions, subscriptions, activations, unsubscribes, support tickets). reported_in_window is the source system's claim; analysis recomputes the window.

- **Kind:** EVENT
- **Primary key:** `conversion_id`
- **Cardinality:** assignment 1:N conversion_event (0..N)
- **Lifecycle / effective dates:** Append-only; ingested_at >= occurred_at models delayed arrival.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `conversion_id` | VARCHAR(24) | yes |  |  | yes | `^CONV-2026-[0-9]{6,}$` |  | Primary key CONV-2026-NNNNNN |
| `assignment_id` | VARCHAR(56) | yes |  |  |  |  | assignment.assignment_id | Attributed assignment |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `user_id` | VARCHAR(16) | yes |  |  |  |  | audience_member.user_id | User |
| `event_type` | VARCHAR(24) | yes | CONVERSION, SUBSCRIPTION, TRIAL_ACTIVATION, UNSUBSCRIBE, SUPPORT_TICKET |  |  |  |  | Outcome type |
| `occurred_at` | DATETIME | yes |  | UTC |  | >= assignment.assigned_at |  | When it happened |
| `ingested_at` | DATETIME | yes |  | UTC |  | >= occurred_at |  | When the warehouse received it |
| `revenue_amount` | DECIMAL(10,2) | no |  | USD |  | > 0 for CONVERSION/SUBSCRIPTION, NULL otherwise |  | Revenue |
| `currency` | CHAR(3) | no | USD |  |  | NOT NULL iff revenue_amount NOT NULL |  | Currency |
| `source_system` | VARCHAR(20) | yes | WEB_CHECKOUT, MOBILE_APP, EMAIL_PLATFORM, SUPPORT_DESK, CRM |  |  |  |  | Producing system |
| `reported_in_window` | BOOLEAN | yes |  |  |  |  |  | Source system claim that the event is inside the outcome window |

## experiment_status_event

Every lifecycle transition of an experiment. Never updated or deleted.

- **Kind:** EVENT (history)
- **Primary key:** `status_event_id`
- **Cardinality:** experiment 1:N experiment_status_event
- **Lifecycle / effective dates:** Chain: from_status = previous to_status.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `status_event_id` | VARCHAR(20) | yes |  |  | yes | `^SEV-2026-[0-9]{6}$` |  | Primary key SEV-2026-NNNNNN |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `from_status` | VARCHAR(16) | no | DRAFT, DESIGNED, RUNNING, STOPPED, ANALYZED, DECIDED, ARCHIVED, CANCELLED |  |  |  |  | Previous state (NULL for creation) |
| `to_status` | VARCHAR(16) | yes | DRAFT, DESIGNED, RUNNING, STOPPED, ANALYZED, DECIDED, ARCHIVED, CANCELLED |  |  |  |  | New state |
| `changed_at` | DATETIME | yes |  | UTC |  |  |  | Transition time |
| `changed_by` | VARCHAR(60) | yes |  |  |  |  |  | Actor |
| `reason` | VARCHAR(300) | no |  |  |  |  |  | Reason |

## analysis_run

One execution of the pre-declared analysis plan. Re-analysis appends a new run; old runs are kept.

- **Kind:** DERIVED
- **Primary key:** `run_id`
- **Cardinality:** experiment 1:N analysis_run; analysis_run 1:N experiment_result
- **Lifecycle / effective dates:** Immutable.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `run_id` | VARCHAR(20) | yes |  |  | yes | `^RUN-2026-[0-9]{6}$` |  | Primary key RUN-2026-NNNNNN |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `primary_metric_def_id` | VARCHAR(64) | yes |  |  |  |  | metric_definition.metric_def_id | Metric version used |
| `computed_at` | DATETIME | yes |  | UTC |  |  |  | Run time |
| `data_cutoff_at` | DATETIME | yes |  | UTC |  |  |  | Events ingested after this are ignored |
| `triggered_by` | VARCHAR(60) | yes |  |  |  |  |  | Actor |
| `analysis_population` | VARCHAR(16) | yes | ITT |  |  |  |  | Population |
| `n_assigned_users` | INT | yes |  | users |  |  |  | Distinct assigned users |
| `n_excluded_multi_assignment` | INT | yes |  | users |  |  |  | Users excluded because assigned to >1 variant |
| `n_analyzed_users` | INT | yes |  | users |  |  |  | = n_assigned_users - n_excluded_multi_assignment |
| `n_exposure_before_assignment` | INT | yes |  | events |  |  |  | Exposures timestamped before their assignment |
| `n_noncompliant_users` | INT | yes |  | users |  |  |  | Analysed users who saw a variant other than the assigned one |
| `n_never_exposed_users` | INT | yes |  | users |  |  |  | Analysed users with no logged exposure |
| `n_missing_telemetry_exposures` | INT | yes |  | events |  |  |  | Exposures with PARTIAL telemetry |
| `n_out_of_window_reported` | INT | yes |  | events |  |  |  | Primary events reported in-window by source but outside the recomputed window |
| `srm_chi2` | DOUBLE | yes |  |  |  |  |  | Chi-square statistic of observed vs planned allocation |
| `srm_df` | INT | yes |  |  |  |  |  | Degrees of freedom (arms - 1) |
| `srm_p_value` | DOUBLE | yes |  |  |  |  |  | SRM p-value |
| `srm_status` | VARCHAR(8) | yes | PASS, FAIL |  |  |  |  | FAIL if srm_p_value < experiment.srm_alpha |
| `balance_platform_p` | DOUBLE | no |  |  |  |  |  | Chi-square independence p-value platform x variant |
| `balance_sessions_smd` | DOUBLE | no |  |  |  |  |  | Max |standardized mean difference| of pre_period_sessions vs control |
| `balance_status` | VARCHAR(8) | yes | PASS, WARN |  |  |  |  | WARN if platform p < 0.01 or |SMD| > 0.1 |
| `recommendation` | VARCHAR(20) | yes | SHIP, DO_NOT_SHIP, INCONCLUSIVE, BLOCKED_GUARDRAIL, INVALID_SRM |  |  |  |  | Engine recommendation |
| `recommended_variant_id` | VARCHAR(32) | no |  |  |  |  | variant.variant_id | Arm recommended for SHIP |
| `metric_version_note` | VARCHAR(300) | no |  |  |  |  |  | Note when a newer metric version exists |
| `notes` | VARCHAR(1000) | no |  |  |  |  |  | Decision trace |

## experiment_result

Per variant x metric estimates for a run. Calculated from source records, never generated independently.

- **Kind:** DERIVED
- **Primary key:** `result_id`
- **Cardinality:** analysis_run 1:N experiment_result
- **Lifecycle / effective dates:** Immutable.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `result_id` | VARCHAR(20) | yes |  |  | yes | `^RES-2026-[0-9]{6}$` |  | Primary key RES-2026-NNNNNN |
| `run_id` | VARCHAR(20) | yes |  |  |  |  | analysis_run.run_id | Analysis run |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `variant_id` | VARCHAR(32) | yes |  |  |  |  | variant.variant_id | Variant |
| `metric_def_id` | VARCHAR(64) | yes |  |  |  |  | metric_definition.metric_def_id | Metric version |
| `guardrail_id` | VARCHAR(40) | no |  |  |  |  | guardrail_metric.guardrail_id | Guardrail (GUARDRAIL rows) |
| `metric_role` | VARCHAR(10) | yes | PRIMARY, GUARDRAIL |  |  |  |  | Role |
| `n_users` | INT | yes |  | users |  |  |  | Denominator |
| `numerator` | DOUBLE | yes |  |  |  |  |  | Converted users (PROPORTION) or sum of per-user means (MEAN) |
| `metric_value` | DOUBLE | yes |  |  |  |  |  | = numerator / n_users |
| `std_dev` | DOUBLE | no |  |  |  |  |  | Sample std-dev of per-user values |
| `control_value` | DOUBLE | no |  |  |  |  |  | Control metric_value (treatment rows) |
| `abs_diff` | DOUBLE | no |  |  |  |  |  | = metric_value - control_value |
| `rel_diff` | DOUBLE | no |  | ratio |  |  |  | = abs_diff / control_value |
| `ci_low` | DOUBLE | no |  |  |  |  |  | Lower CI bound of abs_diff |
| `ci_high` | DOUBLE | no |  |  |  |  |  | Upper CI bound of abs_diff |
| `rel_ci_low` | DOUBLE | no |  | ratio |  |  |  | ci_low / control_value |
| `rel_ci_high` | DOUBLE | no |  | ratio |  |  |  | ci_high / control_value |
| `p_value` | DOUBLE | no |  |  |  |  |  | Two-sided p-value |
| `alpha_applied` | DOUBLE | no |  |  |  |  |  | Alpha after correction |
| `is_significant` | BOOLEAN | no |  |  |  |  |  | p_value < alpha_applied |
| `guardrail_status` | VARCHAR(8) | no | PASS, WARN, FAIL |  |  |  |  | Guardrail verdict |
| `arm_decision` | VARCHAR(20) | no | SHIP, DO_NOT_SHIP, INCONCLUSIVE, BLOCKED_GUARDRAIL, INVALID_SRM |  |  |  |  | Per-arm decision (primary treatment rows) |

## experiment_decision

Human decision taken on a run. Append-only; a reversal is a new decision row.

- **Kind:** EVENT
- **Primary key:** `decision_id`
- **Cardinality:** experiment 1:N experiment_decision; analysis_run 1:N experiment_decision
- **Lifecycle / effective dates:** Immutable.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `decision_id` | VARCHAR(20) | yes |  |  | yes | `^DEC-2026-[0-9]{6}$` |  | Primary key DEC-2026-NNNNNN |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `run_id` | VARCHAR(20) | yes |  |  |  |  | analysis_run.run_id | Run the decision relied on |
| `decision` | VARCHAR(16) | yes | SHIP, DO_NOT_SHIP, ITERATE, ROLLBACK |  |  |  |  | Decision |
| `chosen_variant_id` | VARCHAR(32) | no |  |  |  |  | variant.variant_id | Variant shipped |
| `decided_by` | VARCHAR(60) | yes |  |  |  |  |  | Actor |
| `decided_at` | DATETIME | yes |  | UTC |  | >= analysis_run.computed_at |  | Decision time |
| `srm_acknowledged` | BOOLEAN | yes |  |  |  |  |  | Decision maker acknowledged an SRM failure |
| `guardrails_acknowledged` | BOOLEAN | yes |  |  |  |  |  | Decision maker acknowledged guardrail failures |
| `rationale` | VARCHAR(1000) | no |  |  |  |  |  | Rationale |

## ground_truth

Labels produced by the generator from its documented rules. Never read by the analysis engine or the detectors; only used for evaluation.

- **Kind:** GROUND TRUTH (separate)
- **Primary key:** `gt_id`
- **Cardinality:** Logical reference to any entity via (entity_type, entity_id)
- **Lifecycle / effective dates:** Write-once.

| Column | Type | Required | Allowed values | Unit | Unique | Validation rule | FK | Description |
|---|---|---|---|---|---|---|---|---|
| `gt_id` | VARCHAR(20) | yes |  |  | yes | `^GT-2026-[0-9]{6}$` |  | Primary key GT-2026-NNNNNN |
| `scope` | VARCHAR(12) | yes | ROW, EXPERIMENT |  |  |  |  | ROW-level anomaly or EXPERIMENT-level expectation |
| `issue_type` | VARCHAR(48) | yes | MULTIPLE_VARIANT_ASSIGNMENT, EXPOSURE_BEFORE_ASSIGNMENT, OUTCOME_WINDOW_INCONSISTENT, SAMPLE_RATIO_MISMATCH, SRM_IGNORED, METRIC_DEFINITION_CHANGED_AFTER_RESULT, GUARDRAIL_REGRESSION, NONCOMPLIANCE, MISSING_TELEMETRY, TELEMETRY_DROPPED, CONVERSION_OUTSIDE_WINDOW, EXPECTED_RECOMMENDATION, TRUE_ITT_RELATIVE_LIFT, SCENARIO |  |  |  |  | Label |
| `entity_type` | VARCHAR(24) | yes | EXPERIMENT, VARIANT, ASSIGNMENT, EXPOSURE_EVENT, CONVERSION_EVENT, ANALYSIS_RUN, EXPERIMENT_DECISION |  |  |  |  | Entity referenced |
| `entity_id` | VARCHAR(64) | yes |  |  |  |  |  | Entity id |
| `experiment_id` | VARCHAR(20) | yes |  |  |  |  | experiment.experiment_id | Experiment |
| `expected_value` | VARCHAR(120) | no |  |  |  |  |  | Expected value / decision |
| `notes` | VARCHAR(500) | no |  |  |  |  |  | Generation rule that produced the label |
