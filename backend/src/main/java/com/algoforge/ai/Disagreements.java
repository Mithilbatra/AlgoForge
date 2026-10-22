package com.algoforge.ai;

import com.algoforge.ai.AiOutputs.Review;
import com.algoforge.ai.BigO.Growth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares provider claims with each other and with execution evidence. Disagreements are settled only by
 * evidence (test results, measured scaling, the static estimate when it is not low-confidence), never by
 * trusting one provider or by voting; anything evidence cannot settle is reported as unresolved.
 */
public final class Disagreements {
    private Disagreements() {}

    public record ProviderReview(String provider, Review review) {}

    /**
     * @param empiricalExponent measured growth exponent in terms of n (already divided by the input-size power),
     *                          or null when the benchmark was inconclusive or not run
     */
    public record Evidence(boolean accepted, int testsRun, int testsFailed, int generatedVerified, int generatedFailed,
                           String staticTime, String staticConfidence, String staticSpace, Double empiricalExponent) {}

    public record Item(String topic, Map<String, String> claims, String status, String conclusion) {}

    public static List<Item> resolve(List<ProviderReview> reviews, Evidence ev) {
        List<Item> out = new ArrayList<>();
        if (reviews.isEmpty()) return out;
        correctness(reviews, ev, out);
        time(reviews, ev, out);
        space(reviews, ev, out);
        return out;
    }

    private static void correctness(List<ProviderReview> reviews, Evidence ev, List<Item> out) {
        Map<String, String> claims = claims(reviews, r -> r.correctnessOpinion());
        boolean failing = ev.testsFailed() > 0 || ev.generatedFailed() > 0;
        boolean wellTested = ev.accepted() && ev.testsRun() >= 5 && ev.generatedFailed() == 0;
        String evidence = failing
                ? "Execution shows failures (" + ev.testsFailed() + " judge test(s), " + ev.generatedFailed() + " generated test(s))."
                : wellTested ? "Passed all " + ev.testsRun() + " judge tests and " + ev.generatedVerified()
                        + " verified generated tests. Tests cannot prove correctness, but none contradict it." : null;
        boolean disagree = claims.values().stream().distinct().count() > 1;
        for (var e : claims.entrySet()) {
            boolean contradicted = failing && e.getValue().equals("LIKELY_CORRECT")
                    || wellTested && e.getValue().equals("LIKELY_INCORRECT");
            if (contradicted) {
                out.add(new Item("correctness", Map.of(e.getKey(), e.getValue()), "CONTRADICTED_BY_EVIDENCE",
                        e.getKey() + "'s opinion conflicts with execution. " + evidence));
            }
        }
        if (disagree) {
            out.add(new Item("correctness", claims, evidence == null ? "UNRESOLVED" : "RESOLVED",
                    evidence == null ? "Providers disagree and there is not enough execution evidence to decide." : evidence));
        }
    }

    private static void time(List<ProviderReview> reviews, Evidence ev, List<Item> out) {
        Map<String, String> claims = claims(reviews, Review::timeComplexity);
        Map<String, Optional<Growth>> parsed = new LinkedHashMap<>();
        claims.forEach((k, v) -> parsed.put(k, BigO.parse(v)));
        Double emp = ev.empiricalExponent();
        if (emp != null) {
            for (var e : parsed.entrySet()) {
                if (e.getValue().isPresent() && !e.getValue().get().exponential() && Math.abs(e.getValue().get().nPower() - emp) > 0.6) {
                    out.add(new Item("time complexity", Map.of(e.getKey(), claims.get(e.getKey())), "CONTRADICTED_BY_EVIDENCE",
                            String.format("Measured runtime grows like n^%.2f, which does not fit %s.", emp, claims.get(e.getKey()))));
                }
            }
        }
        if (!disagreeOnClass(parsed)) return;
        if (emp != null) {
            List<String> fit = parsed.entrySet().stream().filter(e -> e.getValue().isPresent()
                    && Math.abs(e.getValue().get().nPower() - emp) <= 0.35).map(Map.Entry::getKey).toList();
            if (fit.size() == 1) {
                out.add(new Item("time complexity", claims, "RESOLVED", String.format(
                        "Measured growth ≈ n^%.2f supports %s's %s (log factors cannot be separated empirically).",
                        emp, fit.getFirst(), claims.get(fit.getFirst()))));
                return;
            }
        }
        Optional<Growth> stat = BigO.parse(ev.staticTime());
        if (!"LOW".equals(ev.staticConfidence()) && stat.isPresent()) {
            List<String> agree = parsed.entrySet().stream().filter(e -> e.getValue().isPresent() && e.getValue().get().sameClass(stat.get()))
                    .map(Map.Entry::getKey).toList();
            if (agree.size() == 1) {
                out.add(new Item("time complexity", claims, "RESOLVED", "The static analysis estimate (" + ev.staticTime()
                        + ") agrees with " + agree.getFirst() + "."));
                return;
            }
        }
        out.add(new Item("time complexity", claims, "UNRESOLVED",
                "Providers disagree and neither the benchmark nor the static analysis settles it. Run the deep evaluation "
                        + "benchmark on a problem with a generator, or reason about the dominant loop yourself."));
    }

    private static void space(List<ProviderReview> reviews, Evidence ev, List<Item> out) {
        Map<String, String> claims = claims(reviews, Review::spaceComplexity);
        Map<String, Optional<Growth>> parsed = new LinkedHashMap<>();
        claims.forEach((k, v) -> parsed.put(k, BigO.parse(v)));
        if (!disagreeOnClass(parsed)) return;
        Optional<Growth> stat = BigO.parse(ev.staticSpace());
        if (stat.isPresent()) {
            List<String> agree = parsed.entrySet().stream().filter(e -> e.getValue().isPresent() && e.getValue().get().sameClass(stat.get()))
                    .map(Map.Entry::getKey).toList();
            if (agree.size() == 1) {
                out.add(new Item("space complexity", claims, "RESOLVED", "The static allocation scan (" + ev.staticSpace()
                        + ") agrees with " + agree.getFirst() + "."));
                return;
            }
        }
        out.add(new Item("space complexity", claims, "UNRESOLVED", "Providers disagree; no measurement separates the claims."));
    }

    private static boolean disagreeOnClass(Map<String, Optional<Growth>> parsed) {
        if (parsed.size() < 2) return false;
        List<Optional<Growth>> vals = List.copyOf(parsed.values());
        Optional<Growth> a = vals.get(0), b = vals.get(1);
        if (a.isEmpty() || b.isEmpty()) return a.isPresent() != b.isPresent();
        return !a.get().sameClass(b.get());
    }

    private static Map<String, String> claims(List<ProviderReview> reviews, java.util.function.Function<Review, String> f) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ProviderReview r : reviews) m.put(r.provider(), f.apply(r.review()));
        return m;
    }
}
