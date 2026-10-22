package com.algoforge.judge;

import java.util.List;

/** Pure verdict logic, kept free of I/O so it is unit-tested directly. */
public final class Verdicts {
    private Verdicts() {}

    public enum Verdict { ACCEPTED, WRONG_ANSWER, RUNTIME_ERROR, TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED, SKIPPED }

    public record Outcome(Verdict verdict, String message) {}

    /** expected == null means "just run it" (custom input): only resource and crash verdicts apply. */
    public static Outcome classify(Sandbox.Exec e, double timeLimitSeconds, long memoryLimitKb, Language lang,
                                   int outputLimitKb, String expected) {
        if (e.wallSeconds() >= timeLimitSeconds - 0.005) {
            return new Outcome(Verdict.TIME_LIMIT_EXCEEDED, "Exceeded " + timeLimitSeconds + " s");
        }
        boolean javaOom = lang == Language.JAVA && e.stderr().contains("java.lang.OutOfMemoryError");
        boolean killed = e.exitCode() == 137;  // SIGKILL before the deadline: the kernel OOM killer
        if (javaOom || killed || e.maxRssKb() > memoryLimitKb + lang.memoryOverheadKb) {
            return new Outcome(Verdict.MEMORY_LIMIT_EXCEEDED, "Peak memory " + e.maxRssKb() / 1024 + " MB");
        }
        if (e.stdoutBytes() >= (long) outputLimitKb * 1024) {
            return new Outcome(Verdict.RUNTIME_ERROR, "Output limit exceeded (" + outputLimitKb / 1024 + " MB)");
        }
        if (e.exitCode() != 0) return new Outcome(Verdict.RUNTIME_ERROR, exitMessage(e.exitCode(), e.stderr()));
        if (expected == null) return new Outcome(Verdict.ACCEPTED, null);
        return sameTokens(e.stdout(), expected)
                ? new Outcome(Verdict.ACCEPTED, null)
                : new Outcome(Verdict.WRONG_ANSWER, "Output differs from the expected answer");
    }

    /** Whitespace-insensitive token comparison, the usual competitive-programming checker. */
    public static boolean sameTokens(String actual, String expected) {
        String[] a = actual.strip().split("\\s+");
        String[] b = expected.strip().split("\\s+");
        return java.util.Arrays.equals(a, b);
    }

    static String exitMessage(int code, String stderr) {
        String signal = switch (code) {
            case 134 -> " (SIGABRT: abort, e.g. failed assertion or uncaught C++ exception)";
            case 136 -> " (SIGFPE: arithmetic error, e.g. division by zero)";
            case 139 -> " (SIGSEGV: invalid memory access or stack overflow)";
            case 153 -> " (SIGXFSZ: output file size limit)";
            default -> "";
        };
        String last = stderr.strip().lines().reduce((x, y) -> y).orElse("");
        if (last.length() > 200) last = last.substring(0, 200);
        return "Exit code " + code + signal + (last.isEmpty() ? "" : ": " + last);
    }

    /** The first failing test decides the submission verdict, as on most judges. */
    public static String overall(List<Verdict> verdicts) {
        for (Verdict v : verdicts) {
            if (v == Verdict.SKIPPED) return Verdict.TIME_LIMIT_EXCEEDED.name();
            if (v != Verdict.ACCEPTED) return v.name();
        }
        return Verdict.ACCEPTED.name();
    }
}
