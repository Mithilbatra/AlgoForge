package com.algoforge.oa;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.oa.OaScoring.Attempt;
import com.algoforge.oa.OaScoring.ProblemScore;
import com.algoforge.oa.OaScoring.ProblemSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class OaScoringTest {
    static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    static final List<ProblemSpec> PROBLEMS = List.of(new ProblemSpec(1, 100), new ProblemSpec(2, 200));

    static Attempt a(long id, long problem, String status, int passed, int total, int minute) {
        return new Attempt(id, problem, status, passed, total, T0.plusSeconds(60L * minute));
    }

    @Test
    void acceptedEarnsFullPointsAndUnansweredScoresZero() {
        List<ProblemScore> s = OaScoring.score(PROBLEMS, List.of(a(1, 1, "ACCEPTED", 5, 5, 3)), true, 0);
        assertThat(s.get(0).score()).isEqualTo(100);
        assertThat(s.get(1).score()).isZero();
        assertThat(s.get(1).attempts()).isZero();
        assertThat(s.get(1).bestSubmissionId()).isNull();
    }

    @Test
    void partialCreditIsProportionalToPassedTests() {
        List<ProblemScore> s = OaScoring.score(PROBLEMS, List.of(a(1, 2, "WRONG_ANSWER", 3, 4, 3)), true, 0);
        assertThat(s.get(1).score()).isEqualTo(150);
    }

    @Test
    void withoutPartialCreditOnlyAcceptedScores() {
        List<ProblemScore> s = OaScoring.score(PROBLEMS, List.of(a(1, 2, "WRONG_ANSWER", 3, 4, 3)), false, 0);
        assertThat(s.get(1).score()).isZero();
    }

    @Test
    void bestAttemptCountsAndPenaltyAppliesToRejectionsBeforeIt() {
        var attempts = List.of(a(1, 1, "WRONG_ANSWER", 1, 5, 1), a(2, 1, "TIME_LIMIT_EXCEEDED", 4, 5, 2),
                a(3, 1, "ACCEPTED", 5, 5, 3), a(4, 1, "WRONG_ANSWER", 0, 5, 4));
        ProblemScore s = OaScoring.score(PROBLEMS, attempts, false, 10).get(0);
        assertThat(s.score()).isEqualTo(80);  // 100 - 2 rejected * 10% * 100; the later WA does not matter
        assertThat(s.bestSubmissionId()).isEqualTo(3);
        assertThat(s.rejected()).isEqualTo(3);
    }

    @Test
    void compilationAndInternalErrorsAreNeitherScoredNorPenalised() {
        var attempts = List.of(a(1, 1, "COMPILATION_ERROR", 0, 0, 1), a(2, 1, "INTERNAL_ERROR", 0, 0, 2), a(3, 1, "ACCEPTED", 5, 5, 3));
        assertThat(OaScoring.score(PROBLEMS, attempts, true, 25).get(0).score()).isEqualTo(100);
    }

    @Test
    void penaltyNeverMakesScoreNegative() {
        var attempts = new ArrayList<Attempt>();
        for (int i = 0; i < 12; i++) attempts.add(a(i, 1, "WRONG_ANSWER", 0, 5, i));
        attempts.add(a(99, 1, "ACCEPTED", 5, 5, 20));
        assertThat(OaScoring.score(PROBLEMS, attempts, true, 50).get(0).score()).isZero();
    }

    @Test
    void scoringIsIndependentOfInputOrderAndTiesPickTheEarliest() {
        var attempts = new ArrayList<>(List.of(a(5, 2, "WRONG_ANSWER", 2, 4, 5), a(3, 2, "WRONG_ANSWER", 2, 4, 2),
                a(7, 1, "ACCEPTED", 5, 5, 9)));
        var first = OaScoring.score(PROBLEMS, attempts, true, 0);
        Collections.reverse(attempts);
        var second = OaScoring.score(PROBLEMS, attempts, true, 0);
        assertThat(second).isEqualTo(first);
        assertThat(first.get(1).bestSubmissionId()).isEqualTo(3);
    }

    @Test
    void pendingAttemptsAreIgnored() {
        var s = OaScoring.score(PROBLEMS, List.of(a(1, 1, "RUNNING", 0, 0, 1)), true, 0);
        assertThat(s.get(0).score()).isZero();
        assertThat(s.get(0).attempts()).isEqualTo(1);
    }
}
