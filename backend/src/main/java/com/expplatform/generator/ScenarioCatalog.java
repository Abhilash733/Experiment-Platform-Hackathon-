package com.expplatform.generator;

import java.util.List;

/**
 * The 10 documented experiment scenarios. Effects are the TRUE per-exposure effects the
 * simulator applies; outcomes are produced from these rules, never assigned at random.
 */
public final class ScenarioCatalog {

    private ScenarioCatalog() {}

    /** One arm. primaryEffect is the relative lift applied to users who actually SEE this arm. */
    public record ArmSpec(String key, String name, String description, double allocPct, double primaryEffect,
                          double unsubMult, double ticketMult, double latencyAddMs) {
        public boolean isControl() { return "A".equals(key); }
    }

    public record ScenarioSpec(String code, String name, String hypothesis, String team, String requestedBy,
                               String primaryMetricCode, double baseline, double mdeRelative, int runDays,
                               double userWeight, double delayMeanDays, double longIngestShare, String surface,
                               String srmBug, boolean injectMultiAssignment, String expectedRecommendation,
                               String expectedVariantKey, String expectedGuardrailFailures, String decisionOverride,
                               List<ArmSpec> arms) {
        public ArmSpec control() { return arms.get(0); }
    }

    private static ArmSpec ctl(double alloc) {
        return new ArmSpec("A", "Control", "Current experience", alloc, 0, 1, 1, 0);
    }

    public static final List<ScenarioSpec> SCENARIOS = List.of(
        new ScenarioSpec("BALANCED_POSITIVE", "One-page checkout",
            "Collapsing checkout into a single page increases 7-day checkout conversion by >= 25% relative without hurting unsubscribe rate, support tickets or page load.",
            "PRODUCT", "pm.checkout", "CHECKOUT_CONVERSION", 0.12, 0.25, 21, 0.10, 1.5, 0.0, "CHECKOUT",
            "NONE", true, "SHIP", "B", "", null,
            List.of(ctl(50), new ArmSpec("B", "Single-page checkout", "Address, shipping and payment on one page", 50, 0.30, 1, 1, 0))),

        new ScenarioSpec("BALANCED_NULL", "Homepage hero copy",
            "A benefit-led hero headline increases 7-day checkout conversion by >= 15% relative.",
            "MARKETING_OPS", "marketing.ops.manager", "CHECKOUT_CONVERSION", 0.10, 0.15, 21, 0.10, 1.5, 0.0, "HOMEPAGE",
            "NONE", true, "INCONCLUSIVE", null, "", null,
            List.of(ctl(50), new ArmSpec("B", "Benefit-led headline", "Hero copy rewritten around customer benefits", 50, 0.0, 1, 1, 0))),

        new ScenarioSpec("UNBALANCED_BY_DESIGN", "Pricing page annual-plan default",
            "Pre-selecting the annual plan increases 7-day checkout conversion by >= 30% relative. 80/20 allocation limits revenue risk.",
            "SALES_OPS", "sales.ops.lead", "CHECKOUT_CONVERSION", 0.08, 0.30, 21, 0.14, 1.5, 0.0, "PRICING_PAGE",
            "NONE", false, "SHIP", "B", "", null,
            List.of(ctl(80), new ArmSpec("B", "Annual plan pre-selected", "Annual plan selected by default", 20, 0.40, 1, 1, 0))),

        new ScenarioSpec("SRM_DETECTED", "Onboarding email cadence",
            "Sending the onboarding series every 2 days instead of weekly increases 7-day checkout conversion by >= 10% relative.",
            "MARKETING_OPS", "lifecycle.marketing", "CHECKOUT_CONVERSION", 0.12, 0.10, 21, 0.10, 1.5, 0.0, "EMAIL",
            "ANDROID_TREATMENT_DROP", false, "INVALID_SRM", null, "", null,
            List.of(ctl(50), new ArmSpec("B", "2-day cadence", "Onboarding emails every 2 days", 50, 0.10, 1, 1, 0))),

        new ScenarioSpec("GUARDRAIL_REGRESSION", "Aggressive upsell modal",
            "An upsell modal after add-to-cart increases 7-day checkout conversion by >= 15% relative while keeping unsubscribe and support-ticket rates within tolerance.",
            "GROWTH", "growth.pm", "CHECKOUT_CONVERSION", 0.12, 0.15, 21, 0.10, 1.5, 0.0, "PRODUCT_PAGE",
            "NONE", false, "BLOCKED_GUARDRAIL", null, "UNSUBSCRIBE_RATE,SUPPORT_TICKET_RATE", null,
            List.of(ctl(50), new ArmSpec("B", "Upsell modal", "Full-screen upsell modal", 50, 0.20, 2.2, 1.6, 30))),

        new ScenarioSpec("DELAYED_CONVERSION", "Free-trial reminder sequence",
            "A 3-step reminder sequence increases 14-day paid subscription conversion by >= 25% relative. Conversions arrive late (mean 6 days) and some are ingested days later.",
            "MARKETING_OPS", "crm.specialist", "SUBSCRIPTION_CONVERSION", 0.10, 0.25, 21, 0.12, 6.0, 0.15, "EMAIL",
            "NONE", false, "SHIP", "B", "", null,
            List.of(ctl(50), new ArmSpec("B", "3-step reminders", "Reminders on day 3, 7 and 12", 50, 0.30, 1, 1, 0))),

        new ScenarioSpec("MULTI_ARM", "Search ranking model",
            "A semantic ranker (B) increases 7-day checkout conversion by >= 25% relative; a popularity ranker (C) is also tested. Bonferroni-corrected across 2 comparisons.",
            "PRODUCT", "search.pm", "CHECKOUT_CONVERSION", 0.15, 0.25, 21, 0.12, 1.5, 0.0, "SEARCH",
            "NONE", true, "SHIP", "B", "AVG_PAGE_LOAD_MS", null,
            List.of(ctl(34), new ArmSpec("B", "Semantic ranker", "Embedding-based ranking", 33, 0.35, 1, 1, 0),
                    new ArmSpec("C", "Popularity ranker", "Ranks by 30-day popularity, heavier backend call", 33, -0.20, 1, 1, 110))),

        new ScenarioSpec("SRM_IGNORED", "Loyalty badge on profile",
            "Showing a loyalty badge increases 7-day checkout conversion by >= 15% relative.",
            "GROWTH", "growth.analyst", "CHECKOUT_CONVERSION", 0.10, 0.15, 21, 0.09, 1.5, 0.0, "APP_HOME",
            "BUCKET_BOUNDARY", false, "INVALID_SRM", null, "", "SHIP_IGNORING_SRM",
            List.of(ctl(50), new ArmSpec("B", "Loyalty badge", "Badge with tier progress", 50, 0.20, 1, 1, 0))),

        new ScenarioSpec("METRIC_CHANGED", "Trial activation checklist",
            "An onboarding checklist increases 7-day trial activation by >= 25% relative. (Metric owner later redefines activation to a 3-day window.)",
            "PRODUCT", "onboarding.pm", "TRIAL_ACTIVATION", 0.14, 0.25, 21, 0.09, 1.5, 0.0, "ONBOARDING",
            "NONE", false, "SHIP", "B", "", null,
            List.of(ctl(50), new ArmSpec("B", "Activation checklist", "5-step checklist in onboarding", 50, 0.30, 1, 1, 0))),

        new ScenarioSpec("NEGATIVE_EFFECT", "Remove free-shipping banner",
            "Removing the free-shipping banner does not reduce 7-day checkout conversion by more than 5% relative.",
            "MARKETING_OPS", "promo.manager", "CHECKOUT_CONVERSION", 0.12, 0.20, 21, 0.08, 1.5, 0.0, "HOMEPAGE",
            "NONE", false, "DO_NOT_SHIP", null, "", null,
            List.of(ctl(50), new ArmSpec("B", "No banner", "Free-shipping banner removed", 50, -0.30, 1, 1, 0)))
    );
}
