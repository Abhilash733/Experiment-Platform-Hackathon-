package com.expplatform.generator;

import com.expplatform.analysis.Stats;
import com.expplatform.generator.ScenarioCatalog.ArmSpec;
import com.expplatform.generator.ScenarioCatalog.ScenarioSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Deterministic synthetic data generator. Same (seed, users, experiments) => byte-identical output.
 * Order: master data (metrics, users, experiments, variants, guardrails) -> bridge (eligibility,
 * assignment) -> events (exposures, conversions) -> labelled edge-case injection -> ground truth.
 * No wall-clock time is used anywhere.
 */
public class SyntheticDataGenerator {

    public static final LocalDateTime BASE_START = LocalDateTime.of(2026, 1, 12, 0, 0);
    public static final LocalDateTime METRIC_EFFECTIVE = LocalDateTime.of(2025, 12, 1, 0, 0);
    public static final String[] PLATFORMS = {"IOS", "ANDROID", "WEB"};
    private static final double[] PLATFORM_W = {0.28, 0.32, 0.40};
    private static final String[] COUNTRIES = {"US", "IN", "GB", "DE", "BR", "CA", "FR", "AU"};
    private static final double[] COUNTRY_W = {0.35, 0.15, 0.12, 0.10, 0.08, 0.08, 0.07, 0.05};
    private static final String[] CHANNELS = {"ORGANIC", "PAID_SEARCH", "EMAIL", "SOCIAL", "REFERRAL", "SALES_OUTREACH"};
    private static final double[] CHANNEL_W = {0.30, 0.22, 0.15, 0.15, 0.10, 0.08};

    // Behavioural constants (documented in README)
    public static final double P_EXPOSED = 0.92;
    public static final double P_TREATMENT_SEES_CONTROL = 0.03;
    public static final double P_CONTROL_SEES_TREATMENT = 0.01;
    public static final double P_TELEMETRY_DROPPED = 0.02;
    public static final double P_PARTIAL_TELEMETRY = 0.04;
    public static final double UNEXPOSED_CONVERSION_FACTOR = 0.6;
    public static final double BASE_UNSUB = 0.03;
    public static final double BASE_TICKET = 0.03;
    public static final double BASE_LATENCY_MS = 780;

    public record MetricSeed(String code, String name, String description, String type, String source,
                             String eventType, String valueField, int windowDays, String unit, String direction) {}

    public static final List<MetricSeed> METRICS = List.of(
        new MetricSeed("CHECKOUT_CONVERSION", "Checkout conversion (7d)",
            "Share of analysed users with >= 1 CONVERSION event in [assigned_at, assigned_at + 7 days), ingested before the data cutoff.",
            "PROPORTION", "CONVERSION_EVENT", "CONVERSION", null, 7, "ratio", "INCREASE"),
        new MetricSeed("SUBSCRIPTION_CONVERSION", "Paid subscription conversion (14d)",
            "Share of analysed users with >= 1 SUBSCRIPTION event in [assigned_at, assigned_at + 14 days).",
            "PROPORTION", "CONVERSION_EVENT", "SUBSCRIPTION", null, 14, "ratio", "INCREASE"),
        new MetricSeed("TRIAL_ACTIVATION", "Trial activation (7d)",
            "Share of analysed users with >= 1 TRIAL_ACTIVATION event in [assigned_at, assigned_at + 7 days).",
            "PROPORTION", "CONVERSION_EVENT", "TRIAL_ACTIVATION", null, 7, "ratio", "INCREASE"),
        new MetricSeed("UNSUBSCRIBE_RATE", "Email unsubscribe rate (7d)",
            "Share of analysed users with >= 1 UNSUBSCRIBE event in [assigned_at, assigned_at + 7 days). Lower is better.",
            "PROPORTION", "CONVERSION_EVENT", "UNSUBSCRIBE", null, 7, "ratio", "DECREASE"),
        new MetricSeed("SUPPORT_TICKET_RATE", "Support ticket rate (7d)",
            "Share of analysed users with >= 1 SUPPORT_TICKET event in [assigned_at, assigned_at + 7 days). Lower is better.",
            "PROPORTION", "CONVERSION_EVENT", "SUPPORT_TICKET", null, 7, "ratio", "DECREASE"),
        new MetricSeed("AVG_PAGE_LOAD_MS", "Average page load time",
            "Mean over analysed users of each user's average page_load_ms across exposures with COMPLETE telemetry. Lower is better.",
            "MEAN", "EXPOSURE_EVENT", null, "page_load_ms", 7, "ms", "DECREASE")
    );

    /** Guardrails attached to every experiment: metric code, tolerated relative degradation, severity. */
    public static final List<Object[]> GUARDRAILS = List.of(
        new Object[]{"UNSUBSCRIBE_RATE", 0.15, "BLOCKING"},
        new Object[]{"SUPPORT_TICKET_RATE", 0.15, "ADVISORY"},
        new Object[]{"AVG_PAGE_LOAD_MS", 0.05, "BLOCKING"});

    public static String metricDefId(String code, int version) {
        return "MDEF-" + code + "-V" + version;
    }

    public static MetricSeed metric(String code) {
        return METRICS.stream().filter(m -> m.code().equals(code)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ hashing
    static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    static long fnv64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 0x100000001b3L; }
        return h;
    }

    /** Deterministic bucket in [0, 9999] = hash(seed, experiment_id, user_id) mod 10000. */
    public static int bucket(long seed, String experimentId, String userId) {
        long h = mix64(seed ^ mix64(fnv64(experimentId)) ^ (mix64(fnv64(userId)) * 31));
        return (int) Long.remainderUnsigned(h, 10000);
    }

    // ------------------------------------------------------------------ random helpers
    private static double gauss(SplittableRandom r) {
        double u1 = 1.0 - r.nextDouble();
        double u2 = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    private static String pick(SplittableRandom r, String[] values, double[] weights) {
        double u = r.nextDouble(), acc = 0;
        for (int i = 0; i < values.length; i++) { acc += weights[i]; if (u < acc) return values[i]; }
        return values[values.length - 1];
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** True ITT relative lift implied by the simulation rules (exposure, noncompliance, unexposed baseline). */
    public static double trueIttLift(ScenarioSpec s, ArmSpec arm) {
        double firstTreatEffect = s.arms().get(1).primaryEffect();
        double unexposed = (1 - P_EXPOSED) * UNEXPOSED_CONVERSION_FACTOR;
        double eT = P_EXPOSED * ((1 - P_TREATMENT_SEES_CONTROL) * (1 + arm.primaryEffect()) + P_TREATMENT_SEES_CONTROL) + unexposed;
        double eC = P_EXPOSED * ((1 - P_CONTROL_SEES_TREATMENT) + P_CONTROL_SEES_TREATMENT * (1 + firstTreatEffect)) + unexposed;
        return eT / eC - 1;
    }

    // ------------------------------------------------------------------ per-assignment memory for edge cases
    private record Assigned(int expIndex, String experimentId, String assignmentId, String userId, String variantKey,
                            String variantId, LocalDateTime assignedAt, LocalDateTime endAt, String platform, String surface) {}

    // ------------------------------------------------------------------ main entry
    public Dataset generate(long seed, int nUsers, int nExperiments) {
        if (nUsers < 200 || nUsers > 999_999) throw new IllegalArgumentException("users must be between 200 and 999999");
        if (nExperiments < 1 || nExperiments > 99) throw new IllegalArgumentException("experiments must be between 1 and 99");

        Dataset ds = new Dataset(seed, nUsers, nExperiments);
        SplittableRandom master = new SplittableRandom(seed);
        int[] expoCounter = {0};
        int[] convCounter = {0};

        // 1. Metric definitions (version 1)
        Map<String, Integer> windowByEventType = new HashMap<>();
        for (MetricSeed m : METRICS) {
            ds.add("metric_definition", "metric_def_id", metricDefId(m.code(), 1), "metric_code", m.code(), "version", 1,
                "name", m.name(), "description", m.description(), "metric_type", m.type(), "source_entity", m.source(),
                "event_type", m.eventType(), "value_field", m.valueField(), "window_days", m.windowDays(), "unit", m.unit(),
                "desired_direction", m.direction(), "effective_from", METRIC_EFFECTIVE, "created_by", "analytics.platform",
                "change_reason", "Initial certified definition");
            if (m.eventType() != null) windowByEventType.put(m.eventType(), m.windowDays());
        }

        // 2. Audience members
        String[] userIds = new String[nUsers];
        String[] platform = new String[nUsers];
        String[] userType = new String[nUsers];
        int[] sessions = new int[nUsers];
        boolean[] consent = new boolean[nUsers];
        LocalDate signupBase = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < nUsers; i++) {
            userIds[i] = String.format("USR-%06d", i + 1);
            userType[i] = master.nextDouble() < 0.45 ? "CUSTOMER" : "PROSPECT";
            String country = pick(master, COUNTRIES, COUNTRY_W);
            platform[i] = pick(master, PLATFORMS, PLATFORM_W);
            String channel = pick(master, CHANNELS, CHANNEL_W);
            LocalDate signup = signupBase.plusDays(master.nextInt(740));
            double meanSessions = "CUSTOMER".equals(userType[i]) ? 11 : 4;
            sessions[i] = (int) Math.floor(-meanSessions * Math.log(1 - master.nextDouble()));
            int orders = "CUSTOMER".equals(userType[i]) ? Math.min(sessions[i], (int) Math.floor(sessions[i] * master.nextDouble() * 0.35)) : 0;
            consent[i] = master.nextDouble() < 0.70;
            LocalDateTime created = signup.atStartOfDay().plusSeconds(master.nextInt(86400));
            ds.add("audience_member", "user_id", userIds[i], "user_type", userType[i], "country", country,
                "platform", platform[i], "acquisition_channel", channel, "signup_date", signup,
                "pre_period_sessions", sessions[i], "pre_period_orders", orders, "marketing_consent", consent[i],
                "created_at", created);
        }

        // 3. Partition users across experiments (each eligible user belongs to exactly one experiment's audience)
        List<ScenarioSpec> specs = new ArrayList<>();
        for (int e = 0; e < nExperiments; e++) specs.add(ScenarioCatalog.SCENARIOS.get(e % ScenarioCatalog.SCENARIOS.size()));
        int[] perm = new int[nUsers];
        for (int i = 0; i < nUsers; i++) perm[i] = i;
        for (int i = nUsers - 1; i > 0; i--) { int j = master.nextInt(i + 1); int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp; }
        double wSum = specs.stream().mapToDouble(ScenarioSpec::userWeight).sum();
        int[] sizes = new int[nExperiments];
        int allocated = 0;
        for (int e = 0; e < nExperiments; e++) {
            sizes[e] = (int) Math.floor(nUsers * specs.get(e).userWeight() / wSum);
            allocated += sizes[e];
        }
        sizes[nExperiments - 1] += nUsers - allocated;

        List<Assigned> allAssigned = new ArrayList<>();
        int offset = 0;

        // 4. Experiments
        for (int e = 0; e < nExperiments; e++) {
            ScenarioSpec s = specs.get(e);
            int wave = e / ScenarioCatalog.SCENARIOS.size();
            String expId = String.format("EXP-2026-%06d", e + 1);
            String name = s.name() + (wave > 0 ? " (wave " + (wave + 1) + ")" : "");
            LocalDateTime start = BASE_START.plusDays((long) (e % 10) * 14 + wave * 3L);
            LocalDateTime end = start.plusDays(s.runDays());
            MetricSeed pm = metric(s.primaryMetricCode());
            int window = pm.windowDays();
            LocalDateTime analysisAt = end.plusDays(window + 2).withHour(10);
            LocalDateTime decisionAt = analysisAt.plusDays(2).withHour(15);
            LocalDateTime createdAt = start.minusDays(20).withHour(9);
            LocalDateTime lockedAt = start.minusDays(3).withHour(16);
            int treatments = s.arms().size() - 1;
            double alpha = 0.05;
            double minShare = s.arms().stream().mapToDouble(ArmSpec::allocPct).min().orElse(50) / 100.0;
            int perArm = Stats.sampleSizePerArm(s.baseline(), s.mdeRelative(), alpha / treatments, 0.8);
            int planned = (int) Math.ceil(perArm / minShare);
            String analyst = "analyst." + s.team().toLowerCase();

            ds.add("experiment", "experiment_id", expId, "name", name, "hypothesis", s.hypothesis(), "owner_team", s.team(),
                "requested_by", s.requestedBy(), "primary_metric_def_id", metricDefId(pm.code(), 1), "randomization_unit", "USER",
                "allocation_method", "HASH_SEEDED", "randomization_seed", seed, "start_at", start, "end_at", end,
                "baseline_rate", s.baseline(), "mde_relative", s.mdeRelative(), "alpha", alpha, "power", 0.8,
                "planned_sample_size", planned, "srm_alpha", 0.001, "analysis_population", "ITT",
                "multiple_testing", treatments > 1 ? "BONFERRONI" : "NONE", "analysis_plan_locked_at", lockedAt,
                "status", "STOPPED", "created_at", createdAt);

            statusEvent(ds, expId, null, "DRAFT", createdAt, s.requestedBy(), "Experiment requested");
            statusEvent(ds, expId, "DRAFT", "DESIGNED", lockedAt, analyst, "Hypothesis, primary metric, guardrails and analysis plan locked");
            statusEvent(ds, expId, "DESIGNED", "RUNNING", start, "sysadmin.platform", "Traffic enabled");
            statusEvent(ds, expId, "RUNNING", "STOPPED", end, "sysadmin.platform", "Planned enrollment window reached");

            Map<String, String> variantIds = new LinkedHashMap<>();
            for (ArmSpec a : s.arms()) {
                String vid = "VAR-" + expId + "-" + a.key();
                variantIds.put(a.key(), vid);
                ds.add("variant", "variant_id", vid, "experiment_id", expId, "variant_key", a.key(), "name", a.name(),
                    "description", a.description(), "is_control", a.isControl(), "planned_allocation_pct", a.allocPct());
            }
            int g = 0;
            for (Object[] gr : GUARDRAILS) {
                g++;
                ds.add("guardrail_metric", "guardrail_id", String.format("GRD-%s-%02d", expId, g), "experiment_id", expId,
                    "metric_def_id", metricDefId((String) gr[0], 1), "max_relative_degradation", gr[1], "severity", gr[2]);
            }
            ds.plans.add(new Dataset.ExperimentPlan(expId, e, s, analysisAt, decisionAt, variantIds));

            // Bucket boundaries from the planned allocation (BUCKET_BOUNDARY bug shifts the control boundary)
            int[] upper = new int[s.arms().size()];
            double cum = 0;
            for (int a = 0; a < s.arms().size(); a++) { cum += s.arms().get(a).allocPct(); upper[a] = (int) Math.round(cum * 100); }
            if ("BUCKET_BOUNDARY".equals(s.srmBug())) upper[0] = 4500;

            int[] block = Arrays.copyOfRange(perm, offset, offset + sizes[e]);
            offset += sizes[e];
            Arrays.sort(block);
            SplittableRandom rng = new SplittableRandom(mix64(seed + 0x9E3779B97F4A7C15L * (e + 1)));
            ArmSpec control = s.control();
            ArmSpec firstTreat = s.arms().get(1);
            long runSeconds = (long) s.runDays() * 86400;

            for (int ui : block) {
                String uid = userIds[ui];
                ds.add("experiment_eligibility", "eligibility_id", "ELG-" + expId + "-" + uid, "experiment_id", expId,
                    "user_id", uid, "eligible_at", start, "eligibility_rule",
                    "Active audience snapshot for " + s.surface().toLowerCase() + " surface", "eligibility_status", "ELIGIBLE");

                int b = bucket(seed, expId, uid);
                int armIdx = 0;
                while (armIdx < upper.length - 1 && b >= upper[armIdx]) armIdx++;
                ArmSpec arm = s.arms().get(armIdx);

                if ("ANDROID_TREATMENT_DROP".equals(s.srmBug()) && !arm.isControl() && "ANDROID".equals(platform[ui])
                        && rng.nextDouble() < 0.45) {
                    continue; // assignment never logged (redirect bug) -> sample ratio mismatch
                }

                LocalDateTime assignedAt = start.plusSeconds(rng.nextLong(runSeconds));
                String asgId = "ASG-" + expId + "-" + uid;
                String vid = variantIds.get(arm.key());
                ds.add("assignment", "assignment_id", asgId, "experiment_id", expId, "user_id", uid, "variant_id", vid,
                    "bucket", b, "assigned_at", assignedAt);
                allAssigned.add(new Assigned(e, expId, asgId, uid, arm.key(), vid, assignedAt, end, platform[ui], s.surface()));

                // ---- exposures (noncompliance + missing telemetry)
                boolean exposed = rng.nextDouble() < P_EXPOSED;
                ArmSpec shown = arm;
                LocalDateTime firstExposure = assignedAt.plusSeconds(rng.nextInt(900));
                if (exposed) {
                    double u = rng.nextDouble();
                    if (!arm.isControl() && u < P_TREATMENT_SEES_CONTROL) shown = control;
                    else if (arm.isControl() && u < P_CONTROL_SEES_TREATMENT) shown = firstTreat;
                    boolean dropped = rng.nextDouble() < P_TELEMETRY_DROPPED;
                    int nExpo = 1 + (rng.nextDouble() < 0.45 ? 1 : 0) + (rng.nextDouble() < 0.15 ? 1 : 0);
                    long secondsLeft = java.time.Duration.between(assignedAt, end).getSeconds();
                    for (int k = 0; k < nExpo; k++) {
                        LocalDateTime t = k == 0 ? firstExposure
                            : (secondsLeft > 3600 ? assignedAt.plusSeconds(3600 + rng.nextLong(secondsLeft - 3600)) : firstExposure.plusSeconds(600L * k));
                        boolean partial = rng.nextDouble() < P_PARTIAL_TELEMETRY;
                        double pf = "IOS".equals(platform[ui]) ? 0.9 : "ANDROID".equals(platform[ui]) ? 1.2 : 1.0;
                        Integer ms = (int) Math.round(BASE_LATENCY_MS * pf * Math.exp(0.3 * gauss(rng) - 0.045) + shown.latencyAddMs());
                        if (dropped) continue;
                        String exId = String.format("EXPOS-2026-%06d", ++expoCounter[0]);
                        ds.add("exposure_event", "exposure_id", exId, "assignment_id", asgId, "experiment_id", expId,
                            "user_id", uid, "variant_id", variantIds.get(shown.key()), "exposed_at", t, "surface", s.surface(),
                            "platform", platform[ui], "page_load_ms", partial ? null : ms,
                            "telemetry_status", partial ? "PARTIAL" : "COMPLETE");
                        if (partial) ds.groundTruth("ROW", "MISSING_TELEMETRY", "EXPOSURE_EVENT", exId, expId, null,
                            "Exposure logged with PARTIAL telemetry (page_load_ms missing), p=" + P_PARTIAL_TELEMETRY);
                    }
                    if (dropped) ds.groundTruth("ROW", "TELEMETRY_DROPPED", "ASSIGNMENT", asgId, expId, null,
                        "User was exposed but all exposure events were lost (p=" + P_TELEMETRY_DROPPED + "); undetectable from source data");
                    if (shown != arm) ds.groundTruth("ROW", "NONCOMPLIANCE", "ASSIGNMENT", asgId, expId, variantIds.get(shown.key()),
                        "Assigned " + arm.key() + " but rendered " + shown.key());
                }

                // ---- outcomes from documented rules
                double seg = ("CUSTOMER".equals(userType[ui]) ? 1.25 : 0.8) * (1 + 0.03 * (Math.min(sessions[ui], 20) - 5));
                double p = s.baseline() * seg * (exposed ? 1 + shown.primaryEffect() : UNEXPOSED_CONVERSION_FACTOR);
                p = Math.max(0, Math.min(0.95, p));
                String evType = pm.eventType();
                if (rng.nextDouble() < p) {
                    LocalDateTime anchor = exposed ? firstExposure : assignedAt.plusSeconds(rng.nextInt(2 * 86400));
                    long delay = (long) (-s.delayMeanDays() * 86400 * Math.log(1 - rng.nextDouble()));
                    LocalDateTime occurred = anchor.plusSeconds(delay);
                    if (!occurred.isAfter(assignedAt.plusDays(window + 14L))) {
                        conversion(ds, rng, convCounter, asgId, expId, uid, evType, occurred, assignedAt, window, platform[ui], s, false);
                        if (rng.nextDouble() < 0.12) {
                            LocalDateTime again = occurred.plusSeconds(86400L + rng.nextLong(19L * 86400));
                            conversion(ds, rng, convCounter, asgId, expId, uid, evType, again, assignedAt, window, platform[ui], s, false);
                        }
                    }
                }
                if (consent[ui]) {
                    double pu = BASE_UNSUB * (exposed ? shown.unsubMult() : 0.5);
                    if (rng.nextDouble() < pu) {
                        LocalDateTime t = assignedAt.plusSeconds(rng.nextLong(7L * 86400 - 3600));
                        conversion(ds, rng, convCounter, asgId, expId, uid, "UNSUBSCRIBE", t, assignedAt, 7, platform[ui], s, false);
                    }
                }
                double pt = BASE_TICKET * (exposed ? shown.ticketMult() : 1);
                if (rng.nextDouble() < pt) {
                    LocalDateTime t = assignedAt.plusSeconds(rng.nextLong(7L * 86400 - 3600));
                    conversion(ds, rng, convCounter, asgId, expId, uid, "SUPPORT_TICKET", t, assignedAt, 7, platform[ui], s, false);
                }
            }

            // Experiment-level ground truth
            ds.groundTruth("EXPERIMENT", "SCENARIO", "EXPERIMENT", expId, expId, s.code(), "Scenario from ScenarioCatalog");
            String expected = s.expectedVariantKey() == null ? s.expectedRecommendation()
                : s.expectedRecommendation() + ":" + variantIds.get(s.expectedVariantKey());
            ds.groundTruth("EXPERIMENT", "EXPECTED_RECOMMENDATION", "EXPERIMENT", expId, expId, expected,
                "Expected engine recommendation given the simulated effects, SRM bug and guardrail multipliers");
            for (ArmSpec a : s.arms()) {
                if (a.isControl()) continue;
                ds.groundTruth("EXPERIMENT", "TRUE_ITT_RELATIVE_LIFT", "VARIANT", variantIds.get(a.key()), expId,
                    String.format("%.4f", trueIttLift(s, a)),
                    "Per-exposure effect " + a.primaryEffect() + " diluted by exposure (" + P_EXPOSED + ") and noncompliance");
            }
            if (!"NONE".equals(s.srmBug())) {
                ds.groundTruth("EXPERIMENT", "SAMPLE_RATIO_MISMATCH", "EXPERIMENT", expId, expId, s.srmBug(),
                    "ANDROID_TREATMENT_DROP".equals(s.srmBug())
                        ? "45% of ANDROID treatment assignments never logged (redirect bug)"
                        : "Control bucket boundary misconfigured at 4500 instead of 5000 (45/55 split)");
            }
            if (!s.expectedGuardrailFailures().isEmpty()) {
                ds.groundTruth("EXPERIMENT", "GUARDRAIL_REGRESSION", "EXPERIMENT", expId, expId, s.expectedGuardrailFailures(),
                    "Treatment multipliers degrade these guardrails beyond tolerance");
            }
        }

        // 5. Labelled edge-case injection (new rows only; baseline rows are never mutated)
        injectEdgeCases(ds, master, allAssigned, specs, convCounter, expoCounter, windowByEventType);
        return ds;
    }

    private void statusEvent(Dataset ds, String expId, String from, String to, LocalDateTime at, String by, String reason) {
        ds.add("experiment_status_event", "status_event_id", String.format("SEV-2026-%06d", ++ds.statusEventCounter),
            "experiment_id", expId, "from_status", from, "to_status", to, "changed_at", at, "changed_by", by, "reason", reason);
    }

    private String conversion(Dataset ds, SplittableRandom rng, int[] counter, String asgId, String expId, String uid,
                              String evType, LocalDateTime occurred, LocalDateTime assignedAt, int window, String platform,
                              ScenarioSpec s, boolean forceReportedInWindow) {
        String id = String.format("CONV-2026-%06d", ++counter[0]);
        boolean inWindow = !occurred.isBefore(assignedAt) && occurred.isBefore(assignedAt.plusDays(window));
        boolean primary = !"UNSUBSCRIBE".equals(evType) && !"SUPPORT_TICKET".equals(evType);
        long lag = primary && s.longIngestShare() > 0 && rng.nextDouble() < s.longIngestShare()
            ? 86400L + rng.nextLong(3L * 86400)
            : (long) (-1200 * Math.log(1 - rng.nextDouble()));
        BigDecimal revenue = null;
        String source;
        switch (evType) {
            case "CONVERSION" -> { revenue = money(Math.exp(Math.log(48) + 0.6 * gauss(rng))); source = "WEB".equals(platform) ? "WEB_CHECKOUT" : "MOBILE_APP"; }
            case "SUBSCRIPTION" -> { double[] prices = {9.99, 19.99, 49.99}; revenue = money(prices[rng.nextInt(3)]); source = "CRM"; }
            case "TRIAL_ACTIVATION" -> source = "WEB".equals(platform) ? "WEB_CHECKOUT" : "MOBILE_APP";
            case "UNSUBSCRIBE" -> source = "EMAIL_PLATFORM";
            default -> source = "SUPPORT_DESK";
        }
        ds.add("conversion_event", "conversion_id", id, "assignment_id", asgId, "experiment_id", expId, "user_id", uid,
            "event_type", evType, "occurred_at", occurred, "ingested_at", occurred.plusSeconds(lag),
            "revenue_amount", revenue, "currency", revenue == null ? null : "USD", "source_system", source,
            "reported_in_window", forceReportedInWindow || inWindow);
        if (primary && !inWindow && !forceReportedInWindow) {
            ds.groundTruth("ROW", "CONVERSION_OUTSIDE_WINDOW", "CONVERSION_EVENT", id, expId, "EXCLUDED",
                "Delayed/repeat conversion after the " + window + "-day outcome window; correctly reported as out-of-window");
        }
        return id;
    }

    private void injectEdgeCases(Dataset ds, SplittableRandom r, List<Assigned> all, List<ScenarioSpec> specs,
                                 int[] convCounter, int[] expoCounter, Map<String, Integer> windowByEventType) {
        int nUsers = ds.users;
        int multiN = Math.max(3, (int) Math.round(nUsers * 0.0012));
        int beforeN = Math.max(3, (int) Math.round(nUsers * 0.0008));
        int windowN = Math.max(3, (int) Math.round(nUsers * 0.0010));
        Set<String> used = new HashSet<>();

        // (a) User assigned to multiple variants
        List<Assigned> multiPool = all.stream().filter(a -> specs.get(a.expIndex()).injectMultiAssignment()).toList();
        for (int i = 0; i < multiN && !multiPool.isEmpty(); i++) {
            Assigned a = multiPool.get(r.nextInt(multiPool.size()));
            if (!used.add(a.assignmentId())) { i--; if (used.size() >= multiPool.size()) break; continue; }
            ScenarioSpec s = specs.get(a.expIndex());
            List<ArmSpec> arms = s.arms();
            int idx = 0;
            for (int k = 0; k < arms.size(); k++) if (arms.get(k).key().equals(a.variantKey())) idx = k;
            ArmSpec other = arms.get((idx + 1) % arms.size());
            String otherVid = "VAR-" + a.experimentId() + "-" + other.key();
            LocalDateTime second = a.assignedAt().plusSeconds(600 + r.nextInt(6 * 3600));
            if (second.isAfter(a.endAt())) second = a.endAt();
            String id2 = a.assignmentId() + "-2";
            ds.add("assignment", "assignment_id", id2, "experiment_id", a.experimentId(), "user_id", a.userId(),
                "variant_id", otherVid, "bucket", bucket(ds.seed, a.experimentId(), a.userId()), "assigned_at", second);
            String exId = String.format("EXPOS-2026-%06d", ++expoCounter[0]);
            ds.add("exposure_event", "exposure_id", exId, "assignment_id", id2, "experiment_id", a.experimentId(),
                "user_id", a.userId(), "variant_id", otherVid, "exposed_at", second.plusSeconds(30), "surface", a.surface(),
                "platform", a.platform(), "page_load_ms", 700 + r.nextInt(300), "telemetry_status", "COMPLETE");
            ds.groundTruth("ROW", "MULTIPLE_VARIANT_ASSIGNMENT", "ASSIGNMENT", id2, a.experimentId(), otherVid,
                "SDK retry re-assigned user to " + other.key() + " after " + a.variantKey() + "; analysis must exclude the user");
        }

        // (b) Exposure before assignment (client clock skew)
        for (int i = 0; i < beforeN; i++) {
            Assigned a = all.get(r.nextInt(all.size()));
            if (used.contains(a.assignmentId())) { i--; continue; }
            used.add(a.assignmentId());
            LocalDateTime t = a.assignedAt().minusSeconds(300 + r.nextInt(36 * 3600));
            String exId = String.format("EXPOS-2026-%06d", ++expoCounter[0]);
            ds.add("exposure_event", "exposure_id", exId, "assignment_id", a.assignmentId(), "experiment_id", a.experimentId(),
                "user_id", a.userId(), "variant_id", a.variantId(), "exposed_at", t, "surface", a.surface(),
                "platform", a.platform(), "page_load_ms", 650 + r.nextInt(400), "telemetry_status", "COMPLETE");
            ds.groundTruth("ROW", "EXPOSURE_BEFORE_ASSIGNMENT", "EXPOSURE_EVENT", exId, a.experimentId(), null,
                "Exposure timestamp precedes assigned_at (client clock skew)");
        }

        // (c) Outcome window inconsistent: late event flagged in-window by the source system
        for (int i = 0; i < windowN; i++) {
            Assigned a = all.get(r.nextInt(all.size()));
            if (used.contains(a.assignmentId())) { i--; continue; }
            used.add(a.assignmentId());
            ScenarioSpec s = specs.get(a.expIndex());
            MetricSeed pm = metric(s.primaryMetricCode());
            int window = windowByEventType.get(pm.eventType());
            LocalDateTime t = a.assignedAt().plusDays(window).plusSeconds(86400L + r.nextInt(9 * 86400));
            String id = conversion(ds, r, convCounter, a.assignmentId(), a.experimentId(), a.userId(), pm.eventType(), t,
                a.assignedAt(), window, a.platform(), s, true);
            ds.groundTruth("ROW", "OUTCOME_WINDOW_INCONSISTENT", "CONVERSION_EVENT", id, a.experimentId(), "EXCLUDE",
                "Occurred after the " + window + "-day window but source reported_in_window = true");
        }
    }
}
