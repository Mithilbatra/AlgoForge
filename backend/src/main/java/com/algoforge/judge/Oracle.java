package com.algoforge.judge;

import com.algoforge.AppProperties;
import com.algoforge.problems.ProblemService.JudgeInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * A Python sandbox holding a problem's generator, input validator and reference solution. These are author-written,
 * except for original twins, whose AI-written code is only trusted after TwinService cross-checks it here against an
 * independent brute force. It runs in the same locked-down sandbox as submissions either way.
 */
public final class Oracle implements AutoCloseable {
    private static final int GEN_FILE_LIMIT_KB = 16 * 1024;

    private final Sandbox box;
    private final JudgeInfo problem;

    private Oracle(Sandbox box, JudgeInfo problem) {
        this.box = box;
        this.problem = problem;
    }

    public static Oracle open(AppProperties.Sandbox cfg, JudgeInfo p) {
        Sandbox box = Sandbox.start(cfg, cfg.images().get("python"), 1024);
        try {
            if (p.generatorPy() != null) box.write("gen.py", p.generatorPy());
            if (p.validatorPy() != null) box.write("validate.py", p.validatorPy());
            if (p.referencePy() != null) box.write("ref.py", p.referencePy());
            return new Oracle(box, p);
        } catch (RuntimeException e) {
            box.close();
            throw e;
        }
    }

    public boolean canGenerate() { return problem.generatorPy() != null; }
    public boolean canValidate() { return problem.validatorPy() != null; }
    public boolean hasReference() { return problem.referencePy() != null; }

    public String generate(long seed, int n) {
        Sandbox.Exec e = box.exec(30, GEN_FILE_LIMIT_KB, "", List.of("python3", "/box/gen.py", String.valueOf(seed), String.valueOf(n)));
        if (e.exitCode() != 0) throw new IllegalStateException("generator failed for seed " + seed + ": " + e.stderr());
        return e.stdout();
    }

    /** Empty when valid, otherwise the validator's complaint. */
    public Optional<String> validate(String input) {
        if (!canValidate()) return Optional.empty();
        Sandbox.Exec e = box.exec(30, 64, input, List.of("python3", "/box/validate.py"));
        if (e.exitCode() == 0) return Optional.empty();
        String msg = e.stderr().strip().lines().reduce((a, b) -> b).orElse("invalid input");
        return Optional.of(msg.length() > 300 ? msg.substring(0, 300) : msg);
    }

    public String reference(String input) {
        Sandbox.Exec e = box.exec(60, GEN_FILE_LIMIT_KB, input, List.of("python3", "/box/ref.py"));
        if (e.exitCode() != 0) throw new IllegalStateException("reference solution failed: " + e.stderr());
        return e.stdout();
    }

    public String generatorVersion() { return hash(problem.generatorPy()); }

    static String hash(String s) {
        if (s == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(d).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() { box.close(); }
}
