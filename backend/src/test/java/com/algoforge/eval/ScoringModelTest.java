package com.algoforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.judge.Language;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScoringModelTest {
    static final Map<String, Integer> W = Map.of("correctness", 35, "time", 20, "space", 15, "robustness", 15, "readability", 10, "language", 5);
    static final StaticAnalyzer.Result CLEAN = StaticAnalyzer.analyze("x = 1\n", Language.PYTHON, null);

    static ScoringModel.Inputs inputs(String status, int passed, int total, int nonSample, Integer time, Integer mem, ScoringModel.Robustness r) {
        return new ScoringModel.Inputs(status, passed, total, nonSample, time, 2000, mem, 256 * 1024, CLEAN, r);
    }

    @Test
    void perfectAcceptedSubmissionWithEnoughTestsIsVerified() {
        var s = ScoringModel.score(inputs("ACCEPTED", 8, 8, 6, 100, 10_000, new ScoringModel.Robustness(45, 45)), W);
        assertThat(s.correctness()).isEqualTo("VERIFIED");
        assertThat(s.overall()).isEqualTo(100.0);
        assertThat(s.dimensions()).allMatch(d -> !d.provisional());
    }

    @Test
    void fewTestsMakeCorrectnessProvisional() {
        var s = ScoringModel.score(inputs("ACCEPTED", 3, 3, 1, 100, 10_000, null), W);
        assertThat(s.correctness()).isEqualTo("PROVISIONAL");
        assertThat(s.dimensions().stream().filter(d -> d.key().equals("correctness")).findFirst().orElseThrow().provisional()).isTrue();
    }

    @Test
    void compilationErrorExcludesUnassessableDimensionsFromTheMean() {
        var s = ScoringModel.score(inputs("COMPILATION_ERROR", 0, 0, 0, null, null, null), W);
        assertThat(s.correctness()).isEqualTo("FAILED");
        assertThat(s.dimensions()).filteredOn(d -> d.key().equals("time")).allMatch(d -> d.score() == null);
        // correctness 0 (w35) + robustness 0 (w15) + readability 100 (w10) + language 100 (w5) over weight 65
        assertThat(s.overall()).isEqualTo(Math.round(1500.0 / 65 * 10) / 10.0);
    }

    @Test
    void timeLimitAndPartialPassReduceScores() {
        var s = ScoringModel.score(inputs("TIME_LIMIT_EXCEEDED", 6, 8, 6, 2000, 10_000, null), W);
        var dims = s.dimensions();
        assertThat(dims.stream().filter(d -> d.key().equals("time")).findFirst().orElseThrow().score()).isEqualTo(10.0);
        assertThat(dims.stream().filter(d -> d.key().equals("correctness")).findFirst().orElseThrow().score()).isEqualTo(75.0);
    }

    @Test
    void weightsAreConfigurable() {
        var onlyCorrectness = Map.of("correctness", 1, "time", 0, "space", 0, "robustness", 0, "readability", 0, "language", 0);
        var s = ScoringModel.score(inputs("WRONG_ANSWER", 1, 4, 3, 100, 10_000, null), onlyCorrectness);
        assertThat(s.overall()).isEqualTo(25.0);
    }
}
