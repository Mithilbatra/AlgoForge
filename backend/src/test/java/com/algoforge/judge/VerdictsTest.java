package com.algoforge.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.judge.Verdicts.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerdictsTest {
    static Sandbox.Exec exec(int code, double wall, long rss, String out, String err) {
        return new Sandbox.Exec(code, wall, rss, out, err, out.length());
    }

    @Test
    void acceptsMatchingTokensIgnoringWhitespace() {
        var o = Verdicts.classify(exec(0, 0.1, 5000, "1  2\n3\n\n", ""), 2, 256 * 1024, Language.CPP, 8192, "1 2 3");
        assertThat(o.verdict()).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    void wrongAnswerWhenTokensDiffer() {
        var o = Verdicts.classify(exec(0, 0.1, 5000, "1 2 4", ""), 2, 256 * 1024, Language.CPP, 8192, "1 2 3");
        assertThat(o.verdict()).isEqualTo(Verdict.WRONG_ANSWER);
    }

    @Test
    void timeLimitWinsOverKilledExitCode() {
        var o = Verdicts.classify(exec(137, 2.0, 1500, "", ""), 2, 256 * 1024, Language.PYTHON, 8192, "1");
        assertThat(o.verdict()).isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
    }

    @Test
    void earlySigkillIsMemoryLimit() {
        var o = Verdicts.classify(exec(137, 0.12, 330_000, "", ""), 2, 256 * 1024, Language.PYTHON, 8192, "1");
        assertThat(o.verdict()).isEqualTo(Verdict.MEMORY_LIMIT_EXCEEDED);
    }

    @Test
    void javaOutOfMemoryErrorIsMemoryLimit() {
        var o = Verdicts.classify(exec(1, 0.3, 200_000, "", "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space"),
                4, 256 * 1024, Language.JAVA, 8192, "1");
        assertThat(o.verdict()).isEqualTo(Verdict.MEMORY_LIMIT_EXCEEDED);
    }

    @Test
    void jvmBaselineMemoryIsNotCountedAgainstTheLimit() {
        var o = Verdicts.classify(exec(0, 0.3, 300_000, "1", ""), 4, 256 * 1024, Language.JAVA, 8192, "1");
        assertThat(o.verdict()).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    void crashIsRuntimeErrorWithSignalExplanation() {
        var o = Verdicts.classify(exec(139, 0.01, 3000, "", ""), 2, 256 * 1024, Language.CPP, 8192, "1");
        assertThat(o.verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(o.message()).contains("SIGSEGV");
    }

    @Test
    void outputFloodIsRuntimeError() {
        var e = new Sandbox.Exec(1, 0.2, 9000, "x", "OSError", 1024 * 1024);
        var o = Verdicts.classify(e, 2, 256 * 1024, Language.PYTHON, 1024, "1");
        assertThat(o.verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(o.message()).contains("Output limit");
    }

    @Test
    void customInputRunsWithoutExpectedOutputAreAccepted() {
        var o = Verdicts.classify(exec(0, 0.1, 5000, "anything", ""), 2, 256 * 1024, Language.CPP, 8192, null);
        assertThat(o.verdict()).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    void firstFailureDecidesOverallVerdict() {
        assertThat(Verdicts.overall(List.of(Verdict.ACCEPTED, Verdict.WRONG_ANSWER, Verdict.TIME_LIMIT_EXCEEDED))).isEqualTo("WRONG_ANSWER");
        assertThat(Verdicts.overall(List.of(Verdict.ACCEPTED, Verdict.ACCEPTED))).isEqualTo("ACCEPTED");
        assertThat(Verdicts.overall(List.of(Verdict.ACCEPTED, Verdict.SKIPPED))).isEqualTo("TIME_LIMIT_EXCEEDED");
    }
}
