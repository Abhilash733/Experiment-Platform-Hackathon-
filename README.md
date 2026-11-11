# Experiment Platform

This project covers the full experiment workflow: design experiments, validate randomization, calculate guardrail metrics, and estimate treatment effects. It runs on a deterministic synthetic dataset of 50,000 users across 10 experiments, with labelled anomalies and a separate ground truth.

| Part | Folder | Stack |
|---|---|---|
| Backend (REST API, generator, analysis engine, validation, exports) | [backend/](backend/) | Spring Boot 3.3, Java 21, JDBC, Apache Commons Math |
| Frontend (analyst / marketing-ops / admin UI) | [frontend/](frontend/) | React 18, Vite 5, React Router |
| Database | MySQL 8, schema `experiment_platform` | user `root` / password `root` |
| Docs | [docs/](docs/) | Data dictionary, reproducibility report |
| Generated files | `exports/` (created at runtime) | CSV, JSON, SQL seed, ground truth, manifest |

---

## 1. Run it

Prerequisites: Java 21, Maven 3.9+, Node 18+, and MySQL 8 running on `localhost:3306` with `root/root`. The database is created automatically.

```bash
# Terminal 1: backend on http://localhost:8089
cd backend
mvn spring-boot:run
#   First start: creates the tables, then generates the 50k-user dataset (~35 s) and validates it.

# Terminal 2: frontend on http://localhost:5173 (proxies /api to :8089)
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

### Generator command (batch / CLI)

```bash
cd backend
# Full dataset: generate, validate, export, then exit
mvn spring-boot:run -Dspring-boot.run.arguments="--app.seed=20261022 --app.users=50000 --app.experiments=10 --generate --export --exit"
# Starter dataset (same generator, smaller scale)
mvn spring-boot:run -Dspring-boot.run.arguments="--app.users=5000 --generate --export --exit"
# Reproducibility: regenerate twice and compare row counts + SHA-256 per table
mvn spring-boot:run -Dspring-boot.run.arguments="--repro --exit"
# Or with the packaged jar
java -jar target/experiment-platform-backend-1.0.0.jar --generate --export --exit
```

To change the schema, start once with `--app.schema.drop-on-start=true`. All settings are in [application.properties](backend/src/main/resources/application.properties).

Tests (no database needed): `cd backend && mvn test` covers same-seed determinism, bucket uniformity, and a multi-seed unbiasedness check of the simulator.

---

## 2. What the UI does

| Page | Actor | Purpose |
|---|---|---|
| **Overview** | everyone | KPIs, all experiments with SRM / guardrail / recommendation / decision, exception banners (e.g. *SRM ignored*), sanity metrics vs ground truth |
| **Experiments → detail** | analyst, marketing/sales ops | Results (forest plot with CIs, p-values, Bonferroni), guardrails (tolerance vs CI), randomization (SRM chi-square chart, covariate balance, noncompliance, telemetry gaps), design and analysis plan, metric versions, decision form, lifecycle history, run history, per-experiment detected issues |
| **Design experiment** | marketing/sales ops, analyst | Hypothesis, primary metric, guardrails, variants and allocation, alpha/power/MDE, live sample-size calculator. Creates a `DRAFT`; lifecycle buttons move it forward. |
| **Data quality & ground truth** | analyst, admin | 168 checks (PK, FK, enums, ID formats, required fields, date order, reconciliation, lifecycle, duplicates, business rules), detector precision/recall vs ground truth, engine decision accuracy, CI coverage |
| **Data explorer** | support agent, analyst | Browse any table, filter by experiment |
| **Data dictionary** | everyone | Types, required, allowed values, units, uniqueness, validation rules, FKs, cardinality, lifecycle |
| **Generator & exports** | system administrator | Generate (starter/full), export CSV/JSON/SQL, reproducibility check, manifest with checksums, generation history |

**Exception paths enforced by the API** (each returns HTTP 409/422 with a code):
`ILLEGAL_TRANSITION` (state machine), `PLAN_LOCK_TOO_LATE`, `NOT_STOPPED` (no analysis before enrollment stops), `USE_WORKFLOW_ENDPOINT`, `SRM_NOT_ACKNOWLEDGED` (shipping on a failed SRM is blocked unless acknowledged), `GUARDRAIL_FAILED` (shipping over a failing BLOCKING guardrail is blocked unless acknowledged), `DESIGN_INVALID` (lists every design error), `NO_ASSIGNMENTS`.

---

## 3. Domain model

```
metric_definition (versioned) 1─N experiment (primary metric, pinned version)
experiment 1─N variant
experiment N─M metric_definition      via guardrail_metric (bridge, holds tolerance and severity)
experiment N─M audience_member        via experiment_eligibility (bridge) and assignment (bridge/event)
assignment 1─N exposure_event, assignment 1─N conversion_event
experiment 1─N experiment_status_event (append-only lifecycle history)
experiment 1─N analysis_run 1─N experiment_result (derived; re-analysis appends)
analysis_run 1─N experiment_decision (append-only)
ground_truth (separate; logical reference via entity_type + entity_id)
```

The full dictionary is in [docs/DATA_DICTIONARY.md](docs/DATA_DICTIONARY.md). It is generated from [Schema.java](backend/src/main/java/com/expplatform/schema/Schema.java), which also produces the MySQL DDL and the generic validation checks, so the documentation and the database cannot drift apart.

**Lifecycle:** `DRAFT → DESIGNED (plan locked) → RUNNING → STOPPED → ANALYZED → DECIDED → ARCHIVED`; `DRAFT/DESIGNED/RUNNING → CANCELLED`. Every transition is a new `experiment_status_event` row. `experiment.status` mirrors the latest event, and validation checks this.

**No history is overwritten:** a metric change is a new `metric_definition` version, a re-analysis is a new `analysis_run`, and a second decision is a new `experiment_decision` row.

### ID conventions
| Entity | Format | Example |
|---|---|---|
| Experiment | `EXP-2026-NNNNNN` | `EXP-2026-000001` |
| Variant | `VAR-<experiment-id>-<key>` | `VAR-EXP-2026-000001-A` |
| User | `USR-NNNNNN` | `USR-000123` |
| Assignment | `ASG-<experiment-id>-<user-id>` (+`-2` for a labelled duplicate) | `ASG-EXP-2026-000001-USR-000123` |
| Exposure / Conversion | `EXPOS-2026-NNNNNN` / `CONV-2026-NNNNNN` | `EXPOS-2026-000001` |
| Eligibility / Guardrail | `ELG-<exp>-<user>` / `GRD-<exp>-NN` | `GRD-EXP-2026-000001-01` |
| Metric version | `MDEF-<CODE>-V<n>` | `MDEF-TRIAL_ACTIVATION-V2` |
| Run / Result / Decision / Status event / Ground truth | `RUN-` / `RES-` / `DEC-` / `SEV-` / `GT-2026-NNNNNN` | `RUN-2026-000004` |

---

## 4. Synthetic data generation

The generator is [SyntheticDataGenerator.java](backend/src/main/java/com/expplatform/generator/SyntheticDataGenerator.java) and the scenarios are in [ScenarioCatalog.java](backend/src/main/java/com/expplatform/generator/ScenarioCatalog.java). It uses a fixed seed `20261022`, never reads the wall clock, and takes its scale as parameters, so the starter and full datasets come from the same code.

1. **Master data first:** 6 metric definitions → 50,000 audience members (type, country, platform, channel, signup, pre-period sessions/orders, consent) → 10 experiments with hypothesis, pinned primary metric, alpha/power/MDE, planned sample size (computed), SRM threshold, ITT, Bonferroni → variants → 3 guardrails each.
2. **Eligibility and randomization:** users are partitioned across experiments. `bucket = hash(seed, experiment_id, user_id) mod 10000` maps to variants by planned allocation.
3. **Events from documented rules:** 92% of users are exposed. Noncompliance: 3% of treatment users see control and 1% of control users see treatment. Missing telemetry: 2% of exposed users lose all exposure events and 4% of exposures are `PARTIAL`. Conversion probability is `baseline × segment multiplier × (1 + effect of the arm actually shown)`, and unexposed users convert at 0.6× baseline. The conversion delay is exponential (mean 1.5 days, or 6 days in the delayed scenario), 12% of converters buy again, and ingestion lag is minutes (or 1–4 days for 15% of events in the delayed scenario). Unsubscribe and support-ticket probabilities scale with the arm shown. Page-load time is lognormal by platform, plus the arm's latency penalty.
4. **Labelled edge cases are injected as new rows** (baseline rows are never mutated). Each one gets a `ground_truth` row:

| Required edge case | How it is produced | Detected by |
|---|---|---|
| User assigned to multiple variants | 60 SDK-retry assignments `ASG-…-2` to another arm | `GROUP BY experiment,user HAVING COUNT(DISTINCT variant)>1`; the analysis excludes these users |
| Exposure before assignment | 40 exposures 5 min–36 h before `assigned_at` (clock skew) | `exposed_at < assigned_at` |
| Outcome window inconsistent | 50 primary events after the window but with `reported_in_window = true` | recomputed window ≠ source flag; the analysis ignores the flag |
| Sample-ratio mismatch | EXP-04: 45% of Android treatment assignments never logged. EXP-08: control bucket boundary 4500 instead of 5000 | chi-square SRM, p < 0.001 |
| SRM ignored | EXP-08 decision `SHIP` with `srm_acknowledged = false` | decision joined to a failing run. The API now blocks this (`SRM_NOT_ACKNOWLEDGED`) |
| Metric definition changes after results | `MDEF-TRIAL_ACTIVATION-V2` (7d → 3d window) effective 10 days after EXP-09's result | newer version with `effective_from > run.computed_at`. Re-analysis keeps the pinned V1 and adds a note |

**Scenario coverage:** balanced positive, balanced null, unbalanced-by-design 80/20, SRM detected, guardrail regression (unsubscribe and support tickets), delayed conversion (14-day window, late ingestion), 3-arm with Bonferroni (one arm has a latency regression), SRM ignored, metric changed, and negative effect.

5. **Derived values are computed from source records** by the analysis engine at a fixed simulated time (`end + window + 2 days`). Decisions and the post-result metric change follow.
6. **Ground truth** (`ground_truth` table, exported to `ground_truth/`) holds row-level anomaly labels, the expected recommendation per experiment, and the true ITT lift per arm (derived analytically from the simulation rules). The engine and the detectors never read it.

### Analysis plan (predeclared, applied by [AnalysisService.java](backend/src/main/java/com/expplatform/analysis/AnalysisService.java))
- Population: ITT by assigned variant. Users with more than one distinct variant are excluded and counted.
- Outcome: a user converts if an event of the metric's type occurs in `[assigned_at, assigned_at + window)` and was ingested before the data cutoff.
- Randomization: Pearson chi-square of observed vs planned split (`srm_alpha = 0.001`), a chi-square test of platform × variant, and the SMD of pre-period sessions.
- Effect: pooled two-proportion z-test with an unpooled CI, plus a relative lift and its CI. MEAN metrics use Welch. Alpha is Bonferroni-corrected across treatment arms.
- Guardrails: **FAIL** if the 95% CI shows degradation beyond tolerance; **WARN** if the point estimate exceeds tolerance, or the degradation is significant but within tolerance. Only BLOCKING guardrails block shipping.
- Recommendation order: `INVALID_SRM` > `SHIP` (best significant positive arm without a blocking FAIL) > `BLOCKED_GUARDRAIL` > `DO_NOT_SHIP` > `INCONCLUSIVE`.

---

## 5. Validation results (seed 20261022, 50,000 users)

| Measure | Result |
|---|---|
| Integrity checks (PK, FK, enums, ID formats, required, uniqueness, date order, reconciliation incl. independent SQL recount of every numerator, lifecycle, duplicates, business rules) | **166 pass, 0 fail** |
| Engine recommendation vs expected (ground truth) | **10 / 10** |
| Anomaly detection, all labelled types | **precision 100%, recall 99.7%** |
| CI coverage of the true ITT lift | **10 / 11** arms |
| Reproducibility (two regenerations, 14 tables) | **identical row counts and SHA-256** ([docs/reproducibility_report.json](docs/reproducibility_report.json)) |

The two warnings are expected, and both are shown in the UI:
- **Noncompliance recall 883/896.** The 13 misses are users who saw the wrong variant but whose exposure telemetry was dropped entirely, so they cannot be detected from source data. That is the point of the `TELEMETRY_DROPPED` label.
- **CI coverage 10/11.** EXP-06 estimates +63% against a true +27%. A test across 8 seeds gives a mean estimate of +29% against the true +27%, so the estimator is unbiased. One miss in 11 is what a 95% interval is expected to produce.

Volumes: 50,000 users / eligibility rows, 49,703 assignments, 71,791 exposures, 9,504 outcome events, 84 results, 5,623 ground-truth labels.

---

## 6. Exports

`POST /api/export` (or `--export`) writes `exports/dataset_seed-<seed>_users-<n>_experiments-<k>/`:

```
data/csv/*.csv  data/json/*.json  data/sql/schema.sql  data/sql/seed.sql
ground_truth/ground_truth.{csv,json}  ground_truth/ground_truth_seed.sql   ← kept separate from inputs
docs/data_dictionary.{md,json}  validation_summary.json  README.md
manifest.json   ← seed, generator command, provenance, row counts, SHA-256 per table and per file
```

**Provenance:** 100% synthetic. No external, personal or confidential data is used. The statistical methods are standard textbook ones.

---

## 7. REST API (port 8089)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/overview` | KPIs, experiments, latest validation, issue counts |
| GET / POST | `/api/experiments` | list / create (design) |
| GET | `/api/experiments/{id}` | full detail |
| POST | `/api/experiments/{id}/transition` | lifecycle `{toStatus, changedBy, reason}` |
| POST | `/api/experiments/{id}/analyze` | append a new analysis run |
| POST | `/api/experiments/{id}/decisions` | record a decision (SRM / guardrail guards) |
| GET | `/api/runs/{runId}` | one run with results |
| GET | `/api/metrics` | metric versions with derived `effective_to` |
| GET | `/api/design/sample-size?baseline=&mde=&alpha=&power=&arms=&minSharePct=` | sample-size calculator |
| POST | `/api/generator/run` | `{seed, users, experiments}` regenerate + validate |
| POST / GET | `/api/validation/run`, `/api/validation/latest`, `/api/validation/issues` | validation |
| GET | `/api/ground-truth/summary` | label counts |
| POST / GET | `/api/export`, `/api/export/manifest` | exports |
| POST | `/api/reproducibility/check` | two-run checksum comparison |
| GET | `/api/dictionary`, `/api/data/{table}` | data dictionary, data browser |

## 8. Notes and limitations
- Regenerating the dataset replaces all dataset tables, including experiments created in the UI. Generation and validation history are kept.
- Experiments created in the UI have no simulated traffic. They demonstrate design, sample size and lifecycle. The generated experiments carry the data.
- There is no authentication. Actors are recorded as free-text `*_by` fields.
