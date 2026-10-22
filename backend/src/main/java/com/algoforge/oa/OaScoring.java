package com.algoforge.oa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deterministic assessment scoring. Per problem: each judged attempt is worth its points if accepted, or
 * points × passed/total with partial credit; the best attempt counts (earliest on ties). With a penalty, each
 * rejected attempt before the best one subtracts penaltyPct% of the problem's points, never below zero.
 * Compilation and internal errors are neither scored nor penalised. Unanswered problems score 0.
 */
public final class OaScoring {
    private OaScoring() {}

    public record ProblemSpec(long problemId, int points) {}

    public record Attempt(long submissionId, long problemId, String status, int passed, int total, Instant at) {}

    public record ProblemScore(long problemId, int points, double score, Long bestSubmissionId, int attempts, int rejected,
                               String bestStatus) {}

    public static List<ProblemScore> score(List<ProblemSpec> problems, List<Attempt> attempts, boolean partialCredit, int penaltyPct) {
        Map<Long, List<Attempt>> byProblem = attempts.stream()
                .sorted(Comparator.comparing(Attempt::at).thenComparing(Attempt::submissionId))
                .collect(Collectors.groupingBy(Attempt::problemId));
        List<ProblemScore> out = new ArrayList<>();
        for (ProblemSpec p : problems) {
            List<Attempt> as = byProblem.getOrDefault(p.problemId(), List.of());
            double best = 0;
            Attempt bestAttempt = null;
            int rejectedBeforeBest = 0, rejected = 0;
            for (Attempt a : as) {
                if (!judged(a.status())) continue;
                double raw = raw(a, p.points(), partialCredit);
                if (bestAttempt == null || raw > best) {
                    best = raw;
                    bestAttempt = a;
                    rejectedBeforeBest = rejected;
                }
                if (!"ACCEPTED".equals(a.status())) rejected++;
            }
            double finalScore = Math.max(0, best - rejectedBeforeBest * p.points() * penaltyPct / 100.0);
            out.add(new ProblemScore(p.problemId(), p.points(), Math.round(finalScore * 100) / 100.0,
                    bestAttempt == null ? null : bestAttempt.submissionId(), as.size(), rejected,
                    bestAttempt == null ? null : bestAttempt.status()));
        }
        return out;
    }

    static boolean judged(String status) {
        return !List.of("QUEUED", "COMPILING", "RUNNING", "COMPILATION_ERROR", "INTERNAL_ERROR").contains(status);
    }

    static double raw(Attempt a, int points, boolean partialCredit) {
        if ("ACCEPTED".equals(a.status())) return points;
        if (!partialCredit || a.total() == 0) return 0;
        return points * (double) a.passed() / a.total();
    }
}
