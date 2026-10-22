package com.algoforge.judge;

import com.algoforge.AppProperties;

/** Compiles one submission once, then runs it on as many inputs as needed inside the same sandbox. */
public final class CodeRunner implements AutoCloseable {
    private final Sandbox box;
    private final Language lang;
    private final AppProperties.Sandbox cfg;

    private CodeRunner(Sandbox box, Language lang, AppProperties.Sandbox cfg) {
        this.box = box;
        this.lang = lang;
        this.cfg = cfg;
    }

    public record Compile(boolean ok, String output) {}

    public static CodeRunner open(AppProperties.Sandbox cfg, Language lang, int problemMemoryMb) {
        return new CodeRunner(Sandbox.start(cfg, cfg.images().get(lang.id), lang.containerMemoryMb(problemMemoryMb)), lang, cfg);
    }

    public Compile compile(String code) {
        box.write(lang.fileName, code);
        Sandbox.Exec e = box.exec(cfg.compileTimeoutSeconds(), 256 * 1024, "", lang.compile);
        String out = excerpt((e.stderr() + e.stdout()).strip(), 8000);
        if (e.wallSeconds() >= cfg.compileTimeoutSeconds() - 0.01) return new Compile(false, "Compilation timed out");
        return new Compile(e.exitCode() == 0, out);
    }

    public Sandbox.Exec run(String input, double timeLimitSeconds, int memoryLimitMb) {
        return box.exec(timeLimitSeconds, cfg.outputLimitKb(), input, lang.runCommand(memoryLimitMb));
    }

    public String version() { return box.version(lang); }

    public Language language() { return lang; }

    public static String excerpt(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "\n… (truncated)";
    }

    @Override
    public void close() { box.close(); }
}
