package com.expplatform.analysis;

import com.expplatform.api.ApiException;
import com.expplatform.repository.Db;
import com.expplatform.service.LifecycleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes the experiment's pre-declared analysis plan:
 * ITT population (users with >1 distinct variant excluded), SRM chi-square, covariate balance,
 * two-proportion z-test / Welch test with (Bonferroni-corrected) CIs, guardrail margins, decision rules.
 * Only source records are read; reported_in_window and ground truth are never used.
 */
@Service
public class AnalysisService {

    private static final String[] PLATFORMS = {"IOS", "ANDROID", "WEB"};
    private final Db db;
    private final LifecycleService lifecycle;

    public AnalysisService(Db db, LifecycleService lifecycle) {
        this.db = db;
        this.lifecycle = lifecycle;
    }

    private record Asg(String assignmentId, String userId, String variantId, LocalDateTime assignedAt) {}

    private record Metric(String id, String code, int version, String type, String eventType, int window,
                          String direction, String guardrailId, double threshold, String severity) {}

    /** Per-variant accumulators for one metric. */
    private static final class Acc {
        long n; double sum; double sumSq;
        void add(double v) { n++; sum += v; sumSq += v * v; }
        double mean() { return n == 0 ? 0 : sum / n; }
        double sd() { return n < 2 ? 0 : Math.sqrt(Math.max(0, (sumSq - sum * sum / n) / (n - 1))); }
    }

    @Transactional
    public synchronized Map<String, Object> analyze(String experimentId, LocalDateTime computedAt, String triggeredBy) {
        Map<String, Object> exp = db.row("SELECT * FROM experiment WHERE experiment_id = ?", experimentId);
        if (exp == null) throw ApiException.notFound("Experiment " + experimentId);
        String status = (String) exp.get("status");
        if (!Set.of("STOPPED", "ANALYZED", "DECIDED", "ARCHIVED").contains(status)) {
            throw ApiException.conflict("NOT_STOPPED", "Analysis runs only after enrollment stopped (status is " + status + ")");
        }
        double alpha = ((Number) exp.get("alpha")).doubleValue();
        double srmAlpha = ((Number) exp.get("srm_alpha")).doubleValue();
        boolean bonferroni = "BONFERRONI".equals(exp.get("multiple_testing"));

        List<Map<String, Object>> variants = db.rows("SELECT * FROM variant WHERE experiment_id = ? ORDER BY variant_key", experimentId);
        int k = variants.size();
        Map<String, Integer> vIdx = new HashMap<>();
        int controlIdx = 0;
        for (int i = 0; i < k; i++) {
            vIdx.put((String) variants.get(i).get("variant_id"), i);
            if (Boolean.TRUE.equals(variants.get(i).get("is_control"))) controlIdx = i;
        }

        Map<String, Object> pmRow = db.row("SELECT * FROM metric_definition WHERE metric_def_id = ?", exp.get("primary_metric_def_id"));
        Metric primary = toMetric(pmRow, null, 0, null);
        List<Metric> guardrails = new ArrayList<>();
        for (Map<String, Object> g : db.rows("SELECT g.guardrail_id, g.max_relative_degradation, g.severity, m.* FROM guardrail_metric g "
                + "JOIN metric_definition m ON m.metric_def_id = g.metric_def_id WHERE g.experiment_id = ? ORDER BY g.guardrail_id", experimentId)) {
            guardrails.add(toMetric(g, (String) g.get("guardrail_id"), ((Number) g.get("max_relative_degradation")).doubleValue(), (String) g.get("severity")));
        }

        // ---------- population
        Map<String, Asg> asgById = new HashMap<>();
        Map<String, List<Asg>> byUser = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows("SELECT assignment_id, user_id, variant_id, assigned_at FROM assignment WHERE experiment_id = ? ORDER BY assigned_at, assignment_id", experimentId)) {
            Asg a = new Asg((String) r.get("assignment_id"), (String) r.get("user_id"), (String) r.get("variant_id"), (LocalDateTime) r.get("assigned_at"));
            asgById.put(a.assignmentId(), a);
            byUser.computeIfAbsent(a.userId(), x -> new ArrayList<>()).add(a);
        }
        if (byUser.isEmpty()) throw ApiException.invalid("NO_ASSIGNMENTS", "Experiment " + experimentId + " has no assignments to analyse");
        Map<String, Asg> analyzed = new LinkedHashMap<>();
        int excludedMulti = 0;
        for (var e : byUser.entrySet()) {
            Set<String> vs = new HashSet<>();
            for (Asg a : e.getValue()) vs.add(a.variantId());
            if (vs.size() > 1) excludedMulti++;
            else analyzed.put(e.getKey(), e.getValue().get(0));
        }

        // ---------- covariates
        long[][] platformTable = new long[k][PLATFORMS.length];
        Acc[] sessions = newAccs(k);
        for (Map<String, Object> r : db.rows("SELECT m.user_id, m.platform, m.pre_period_sessions FROM audience_member m "
                + "JOIN (SELECT DISTINCT user_id FROM assignment WHERE experiment_id = ?) a ON a.user_id = m.user_id", experimentId)) {
            Asg a = analyzed.get((String) r.get("user_id"));
            if (a == null) continue;
            int vi = vIdx.get(a.variantId());
            String pf = (String) r.get("platform");
            for (int p = 0; p < PLATFORMS.length; p++) if (PLATFORMS[p].equals(pf)) platformTable[vi][p]++;
            sessions[vi].add(((Number) r.get("pre_period_sessions")).doubleValue());
        }

        // ---------- exposures
        int exposureBefore = 0, missingTelemetry = 0;
        Set<String> exposedUsers = new HashSet<>();
        Set<String> noncompliant = new HashSet<>();
        Map<String, double[]> latency = new HashMap<>(); // user -> {sum, count}
        for (Map<String, Object> r : db.rows("SELECT assignment_id, user_id, variant_id, exposed_at, page_load_ms, telemetry_status FROM exposure_event WHERE experiment_id = ?", experimentId)) {
            Asg own = asgById.get((String) r.get("assignment_id"));
            LocalDateTime at = (LocalDateTime) r.get("exposed_at");
            if (own != null && at.isBefore(own.assignedAt())) exposureBefore++;
            if ("PARTIAL".equals(r.get("telemetry_status"))) missingTelemetry++;
            String uid = (String) r.get("user_id");
            Asg a = analyzed.get(uid);
            if (a == null || !a.assignmentId().equals(r.get("assignment_id"))) continue;
            exposedUsers.add(uid);
            if (!a.variantId().equals(r.get("variant_id"))) noncompliant.add(uid);
            Object ms = r.get("page_load_ms");
            if (ms != null) {
                double[] acc = latency.computeIfAbsent(uid, x -> new double[2]);
                acc[0] += ((Number) ms).doubleValue();
                acc[1]++;
            }
        }

        // ---------- conversions (window recomputed from assigned_at; data cutoff applied)
        List<Metric> proportionMetrics = new ArrayList<>();
        proportionMetrics.add(primary);
        for (Metric g : guardrails) if ("PROPORTION".equals(g.type())) proportionMetrics.add(g);
        Map<String, Set<String>> converted = new HashMap<>();
        for (Metric m : proportionMetrics) converted.put(m.id() + "|" + m.guardrailId(), new HashSet<>());
        int outOfWindowReported = 0;
        for (Map<String, Object> r : db.rows("SELECT user_id, event_type, occurred_at, reported_in_window FROM conversion_event WHERE experiment_id = ? AND ingested_at <= ?", experimentId, computedAt)) {
            String uid = (String) r.get("user_id");
            List<Asg> userAsg = byUser.get(uid);
            if (userAsg == null) continue;
            LocalDateTime occurred = (LocalDateTime) r.get("occurred_at");
            String et = (String) r.get("event_type");
            LocalDateTime assignedAt = userAsg.get(0).assignedAt();
            if (et.equals(primary.eventType()) && Boolean.TRUE.equals(r.get("reported_in_window"))
                    && !inWindow(occurred, assignedAt, primary.window())) outOfWindowReported++;
            if (!analyzed.containsKey(uid)) continue;
            for (Metric m : proportionMetrics) {
                if (et.equals(m.eventType()) && inWindow(occurred, assignedAt, m.window())) converted.get(m.id() + "|" + m.guardrailId()).add(uid);
            }
        }

        // ---------- SRM
        long[] observed = new long[k];
        for (Asg a : analyzed.values()) observed[vIdx.get(a.variantId())]++;
        long total = analyzed.size();
        double chi2 = 0;
        for (int i = 0; i < k; i++) {
            double expected = total * ((Number) variants.get(i).get("planned_allocation_pct")).doubleValue() / 100.0;
            if (expected > 0) chi2 += Math.pow(observed[i] - expected, 2) / expected;
        }
        int df = k - 1;
        double srmP = Stats.chiSquareP(chi2, df);
        boolean srmFail = srmP < srmAlpha;

        // ---------- balance
        double platformP = Stats.contingency(platformTable)[2];
        double maxSmd = 0;
        for (int i = 0; i < k; i++) {
            if (i == controlIdx) continue;
            double pooled = Math.sqrt((Math.pow(sessions[i].sd(), 2) + Math.pow(sessions[controlIdx].sd(), 2)) / 2);
            if (pooled > 0) maxSmd = Math.max(maxSmd, Math.abs(sessions[i].mean() - sessions[controlIdx].mean()) / pooled);
        }
        boolean balanceWarn = platformP < 0.01 || maxSmd > 0.1;

        // ---------- per-variant accumulators per metric
        List<Metric> allMetrics = new ArrayList<>();
        allMetrics.add(primary);
        allMetrics.addAll(guardrails);
        Map<String, Acc[]> accs = new LinkedHashMap<>();
        for (Metric m : allMetrics) {
            Acc[] arr = newAccs(k);
            String key = m.id() + "|" + m.guardrailId();
            if ("PROPORTION".equals(m.type())) {
                Set<String> conv = converted.get(key);
                for (Asg a : analyzed.values()) arr[vIdx.get(a.variantId())].add(conv.contains(a.userId()) ? 1 : 0);
            } else {
                for (Asg a : analyzed.values()) {
                    double[] l = latency.get(a.userId());
                    if (l != null && l[1] > 0) arr[vIdx.get(a.variantId())].add(l[0] / l[1]);
                }
            }
            accs.put(key, arr);
        }

        // ---------- results
        String runId = db.nextId("analysis_run", "run_id", "RUN-2026-", 6);
        String firstRes = db.nextId("experiment_result", "result_id", "RES-2026-", 6);
        int resCounter = Integer.parseInt(firstRes.substring("RES-2026-".length()));
        int treatments = k - 1;
        double alphaPrimary = bonferroni && treatments > 1 ? alpha / treatments : alpha;
        List<Map<String, Object>> results = new ArrayList<>();
        String[] armDecision = new String[k];
        double[] primaryRel = new double[k];
        boolean[] blocked = new boolean[k];
        StringBuilder trace = new StringBuilder();

        // guardrails first (arm decisions depend on them)
        List<Map<String, Object>> guardrailRows = new ArrayList<>();
        for (Metric g : guardrails) {
            Acc[] arr = accs.get(g.id() + "|" + g.guardrailId());
            for (int i = 0; i < k; i++) {
                Map<String, Object> row = baseRow(runId, experimentId, (String) variants.get(i).get("variant_id"), g, "GUARDRAIL", arr[i]);
                if (i != controlIdx) {
                    fillComparison(row, g.type(), arr[controlIdx], arr[i], 0.05);
                    String gs = guardrailStatus(row, g);
                    row.put("guardrail_status", gs);
                    if ("FAIL".equals(gs) && "BLOCKING".equals(g.severity())) blocked[i] = true;
                    if (!"PASS".equals(gs)) trace.append(variants.get(i).get("variant_key")).append(' ').append(g.code()).append(' ').append(gs).append("; ");
                }
                guardrailRows.add(row);
            }
        }
        Acc[] parr = accs.get(primary.id() + "|null");
        for (int i = 0; i < k; i++) {
            Map<String, Object> row = baseRow(runId, experimentId, (String) variants.get(i).get("variant_id"), primary, "PRIMARY", parr[i]);
            if (i != controlIdx) {
                fillComparison(row, primary.type(), parr[controlIdx], parr[i], alphaPrimary);
                boolean sig = Boolean.TRUE.equals(row.get("is_significant"));
                double rel = row.get("rel_diff") == null ? 0 : (Double) row.get("rel_diff");
                double good = "INCREASE".equals(primary.direction()) ? rel : -rel;
                primaryRel[i] = good;
                String d;
                if (srmFail) d = "INVALID_SRM";
                else if (blocked[i]) d = "BLOCKED_GUARDRAIL";
                else if (sig && good > 0) d = "SHIP";
                else if (sig) d = "DO_NOT_SHIP";
                else d = "INCONCLUSIVE";
                armDecision[i] = d;
                row.put("arm_decision", d);
                trace.append(variants.get(i).get("variant_key")).append(String.format(" primary %+.1f%% (p=%.4g, alpha=%.4g) -> %s; ",
                    rel * 100, (Double) row.get("p_value"), alphaPrimary, d));
            }
            results.add(row);
        }
        results.addAll(guardrailRows);

        // ---------- recommendation
        String recommendation;
        String recommendedVariant = null;
        if (srmFail) {
            recommendation = "INVALID_SRM";
        } else {
            int best = -1;
            for (int i = 0; i < k; i++) if ("SHIP".equals(armDecision[i]) && (best < 0 || primaryRel[i] > primaryRel[best])) best = i;
            boolean anyBlocked = false, allNegative = true;
            for (int i = 0; i < k; i++) {
                if (i == controlIdx) continue;
                if ("BLOCKED_GUARDRAIL".equals(armDecision[i])) anyBlocked = true;
                if (!"DO_NOT_SHIP".equals(armDecision[i])) allNegative = false;
            }
            if (best >= 0) { recommendation = "SHIP"; recommendedVariant = (String) variants.get(best).get("variant_id"); }
            else if (anyBlocked) recommendation = "BLOCKED_GUARDRAIL";
            else if (allNegative) recommendation = "DO_NOT_SHIP";
            else recommendation = "INCONCLUSIVE";
        }

        String versionNote = null;
        for (Map<String, Object> v : db.rows("SELECT metric_def_id, version, effective_from FROM metric_definition WHERE metric_code = ? AND version > ? AND effective_from <= ? ORDER BY version",
                primary.code(), primary.version(), computedAt)) {
            versionNote = "Analysis plan pins " + primary.id() + "; newer " + v.get("metric_def_id") + " effective since "
                + v.get("effective_from") + " was NOT used (predeclared plan).";
        }

        String notes = String.format("SRM chi2=%.2f df=%d p=%.3g (%s); balance platform p=%.3g, sessions SMD=%.3f; excluded %d multi-variant users; %s=> %s",
            chi2, df, srmP, srmFail ? "FAIL" : "PASS", platformP, maxSmd, excludedMulti, trace, recommendation);
        if (notes.length() > 1000) notes = notes.substring(0, 997) + "...";

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", runId);
        run.put("experiment_id", experimentId);
        run.put("primary_metric_def_id", primary.id());
        run.put("computed_at", computedAt);
        run.put("data_cutoff_at", computedAt);
        run.put("triggered_by", triggeredBy);
        run.put("analysis_population", "ITT");
        run.put("n_assigned_users", byUser.size());
        run.put("n_excluded_multi_assignment", excludedMulti);
        run.put("n_analyzed_users", analyzed.size());
        run.put("n_exposure_before_assignment", exposureBefore);
        run.put("n_noncompliant_users", noncompliant.size());
        run.put("n_never_exposed_users", analyzed.size() - exposedUsers.size());
        run.put("n_missing_telemetry_exposures", missingTelemetry);
        run.put("n_out_of_window_reported", outOfWindowReported);
        run.put("srm_chi2", chi2);
        run.put("srm_df", df);
        run.put("srm_p_value", srmP);
        run.put("srm_status", srmFail ? "FAIL" : "PASS");
        run.put("balance_platform_p", platformP);
        run.put("balance_sessions_smd", maxSmd);
        run.put("balance_status", balanceWarn ? "WARN" : "PASS");
        run.put("recommendation", recommendation);
        run.put("recommended_variant_id", recommendedVariant);
        run.put("metric_version_note", versionNote);
        run.put("notes", notes);
        insertMap("analysis_run", run);

        for (Map<String, Object> r : results) {
            r.put("result_id", String.format("RES-2026-%06d", resCounter++));
            insertMap("experiment_result", r);
        }

        if ("STOPPED".equals(status)) {
            lifecycle.transition(experimentId, "ANALYZED", computedAt, triggeredBy, "Analysis " + runId + " completed: " + recommendation);
        }
        Map<String, Object> out = new LinkedHashMap<>(run);
        out.put("results", results);
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private static boolean inWindow(LocalDateTime occurred, LocalDateTime assignedAt, int windowDays) {
        return !occurred.isBefore(assignedAt) && occurred.isBefore(assignedAt.plusDays(windowDays));
    }

    private static Acc[] newAccs(int k) {
        Acc[] a = new Acc[k];
        for (int i = 0; i < k; i++) a[i] = new Acc();
        return a;
    }

    private static Metric toMetric(Map<String, Object> m, String guardrailId, double threshold, String severity) {
        return new Metric((String) m.get("metric_def_id"), (String) m.get("metric_code"), ((Number) m.get("version")).intValue(),
            (String) m.get("metric_type"), (String) m.get("event_type"), ((Number) m.get("window_days")).intValue(),
            (String) m.get("desired_direction"), guardrailId, threshold, severity);
    }

    private static Map<String, Object> baseRow(String runId, String expId, String variantId, Metric m, String role, Acc a) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("run_id", runId);
        r.put("experiment_id", expId);
        r.put("variant_id", variantId);
        r.put("metric_def_id", m.id());
        r.put("guardrail_id", m.guardrailId());
        r.put("metric_role", role);
        r.put("n_users", (int) a.n);
        r.put("numerator", a.sum);
        r.put("metric_value", a.mean());
        r.put("std_dev", "MEAN".equals(m.type()) ? a.sd() : null);
        return r;
    }

    /** Fills difference, CI, p-value. PROPORTION: unpooled CI + pooled z-test. MEAN: Welch normal approximation. */
    private static void fillComparison(Map<String, Object> row, String type, Acc c, Acc t, double alphaApplied) {
        double mc = c.mean(), mt = t.mean();
        double diff = mt - mc;
        double se, z;
        if ("PROPORTION".equals(type)) {
            se = Math.sqrt(mc * (1 - mc) / Math.max(1, c.n) + mt * (1 - mt) / Math.max(1, t.n));
            double pooled = (c.sum + t.sum) / Math.max(1, c.n + t.n);
            double sep = Math.sqrt(pooled * (1 - pooled) * (1.0 / Math.max(1, c.n) + 1.0 / Math.max(1, t.n)));
            z = sep > 0 ? diff / sep : 0;
        } else {
            se = Math.sqrt(Math.pow(c.sd(), 2) / Math.max(1, c.n) + Math.pow(t.sd(), 2) / Math.max(1, t.n));
            z = se > 0 ? diff / se : 0;
        }
        double zc = Stats.zQuantile(1 - alphaApplied / 2);
        double p = Stats.twoSidedP(z);
        row.put("control_value", mc);
        row.put("abs_diff", diff);
        row.put("ci_low", diff - zc * se);
        row.put("ci_high", diff + zc * se);
        if (mc != 0) {
            row.put("rel_diff", diff / mc);
            row.put("rel_ci_low", (diff - zc * se) / mc);
            row.put("rel_ci_high", (diff + zc * se) / mc);
        }
        row.put("p_value", p);
        row.put("alpha_applied", alphaApplied);
        row.put("is_significant", p < alphaApplied);
    }

    /**
     * FAIL: the CI shows degradation beyond the tolerance (bad-direction lower bound > threshold).
     * WARN: point estimate beyond tolerance, or degradation is significant but within tolerance.
     */
    private static String guardrailStatus(Map<String, Object> row, Metric g) {
        if (row.get("rel_diff") == null) return "WARN";
        double rel = (Double) row.get("rel_diff");
        double lo = (Double) row.get("rel_ci_low"), hi = (Double) row.get("rel_ci_high");
        boolean decreaseGood = "DECREASE".equals(g.direction());
        double badPoint = decreaseGood ? rel : -rel;
        double badLow = decreaseGood ? lo : -hi;
        if (badLow > g.threshold()) return "FAIL";
        if (badPoint > g.threshold() || badLow > 0) return "WARN";
        return "PASS";
    }

    private void insertMap(String table, Map<String, Object> m) {
        List<String> cols = new ArrayList<>(m.keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + String.join(",", cols.stream().map(c -> "?").toList()) + ")";
        db.update(sql, m.values().toArray());
    }
}
