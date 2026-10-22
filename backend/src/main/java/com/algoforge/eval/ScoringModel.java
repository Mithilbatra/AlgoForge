package com.algoforge.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transparent scoring: each dimension carries its weight, score (null = not assessable) and the evidence behind it.
 * The overall score is the weight-normalised mean of the assessable dimensions.
 */
public final class ScoringModel {
    private ScoringModel() {}

    public record Dimension(String key, String label, int weight, Double score, String evidence, boolean provisional) {}

    public record Score(double overall, String correctness, List<Dimension> dimensions) {}

    /** Edge-case evidence from generated tests checked against the reference solution (deep evaluation). */
    public record Robustness(int verifiedPassed, int verifiedTotal) {}

    public record Inputs(String status, int passed, int total, int nonSampleTests, Integer maxTimeMs, double timeLimitMs,
                         Integer maxMemoryKb, long memoryLimitKb, StaticAnalyzer.Result analysis, Robustness robustness) {}

    static final int MIN_TESTS_FOR_VERIFIED = 5;

    public static Score score(Inputs in, Map<String, Integer> weights) {
        boolean compiled = !"COMPILATION_ERROR".equals(in.status());
        boolean accepted = "ACCEPTED".equals(in.status());
        String correctness = !accepted ? "FAILED" : in.nonSampleTests() >= MIN_TESTS_FOR_VERIFIED ? "VERIFIED" : "PROVISIONAL";
        List<Dimension> dims = new ArrayList<>();

        double corr = in.total() == 0 ? 0 : 100.0 * in.passed() / in.total();
        String corrEvidence = !compiled ? "Did not compile."
                : "Passed " + in.passed() + "/" + in.total() + " tests (" + in.nonSampleTests() + " beyond the samples)."
                + ("PROVISIONAL".equals(correctness) ? " Too few tests for a verified result." : "");
        dims.add(new Dimension("correctness", "Correctness & test coverage", w(weights, "correctness"), corr, corrEvidence,
                "PROVISIONAL".equals(correctness)));

        Double time = null;
        String timeEvidence = "Not assessable without a run.";
        if (compiled && in.maxTimeMs() != null) {
            double r = in.maxTimeMs() / in.timeLimitMs();
            boolean tle = "TIME_LIMIT_EXCEEDED".equals(in.status());
            time = tle ? 10.0 : r <= 0.25 ? 100.0 : r <= 0.5 ? 90.0 : r <= 0.75 ? 75.0 : 60.0;
            timeEvidence = (tle ? "Exceeded the time limit. " : "Slowest test took " + in.maxTimeMs() + " ms of a "
                    + Math.round(in.timeLimitMs()) + " ms limit. ") + "Static estimate " + in.analysis().timeComplexity() + ".";
        }
        dims.add(new Dimension("time", "Time complexity", w(weights, "time"), time, timeEvidence, false));

        Double space = null;
        String spaceEvidence = "Not assessable without a run.";
        if (compiled && in.maxMemoryKb() != null) {
            double r = in.maxMemoryKb() / (double) in.memoryLimitKb();
            boolean mle = "MEMORY_LIMIT_EXCEEDED".equals(in.status());
            space = mle ? 10.0 : r <= 0.25 ? 100.0 : r <= 0.5 ? 85.0 : 70.0;
            spaceEvidence = (mle ? "Exceeded the memory limit. " : "Peak resident memory " + in.maxMemoryKb() / 1024 + " MB of "
                    + in.memoryLimitKb() / 1024 + " MB (includes the language runtime). ") + "Auxiliary space estimate " + in.analysis().spaceComplexity() + ".";
        }
        dims.add(new Dimension("space", "Space complexity", w(weights, "space"), space, spaceEvidence, false));

        long riskFindings = in.analysis().findings().stream().filter(f -> f.category().equals("robustness") && f.severity().equals("WARN")).count();
        Double robust;
        String robustEvidence;
        boolean provisional;
        if (in.robustness() != null && in.robustness().verifiedTotal() > 0) {
            robust = 100.0 * in.robustness().verifiedPassed() / in.robustness().verifiedTotal();
            robustEvidence = "Passed " + in.robustness().verifiedPassed() + "/" + in.robustness().verifiedTotal()
                    + " generated edge/random tests checked against the reference solution.";
            provisional = false;
        } else {
            robust = compiled ? corr : 0.0;
            robustEvidence = "Provisional: mirrors the official tests until a deep evaluation runs generated edge cases.";
            provisional = true;
        }
        if (riskFindings > 0 && robust != null) {
            robust = Math.max(0, robust - 15 * riskFindings);
            robustEvidence += " −" + 15 * riskFindings + " for " + riskFindings + " static robustness risk(s).";
        }
        dims.add(new Dimension("robustness", "Robustness & edge cases", w(weights, "robustness"), robust, robustEvidence, provisional));

        dims.add(new Dimension("readability", "Readability & maintainability", w(weights, "readability"),
                (double) in.analysis().readabilityScore(), String.join("; ", in.analysis().readabilityNotes()) + " (heuristic).", false));
        long langFindings = in.analysis().findings().stream().filter(f -> f.category().equals("language")).count();
        dims.add(new Dimension("language", "Language-specific quality", w(weights, "language"),
                (double) in.analysis().languageScore(),
                langFindings == 0 ? "No language pitfalls detected." : langFindings + " language pitfall(s); see findings.", false));

        double sumW = 0, sum = 0;
        for (Dimension d : dims) {
            if (d.score() == null) continue;
            sumW += d.weight();
            sum += d.weight() * d.score();
        }
        double overall = sumW == 0 ? 0 : Math.round(sum / sumW * 10) / 10.0;
        return new Score(overall, correctness, dims);
    }

    private static int w(Map<String, Integer> weights, String key) {
        Integer v = weights.get(key);
        if (v == null || v < 0) throw new IllegalStateException("missing scoring weight " + key);
        return v;
    }
}
