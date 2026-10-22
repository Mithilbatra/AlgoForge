package com.algoforge.judge;

import com.algoforge.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One disposable container per job. Isolation layers: no network, read-only root, tmpfs-only writable paths,
 * unprivileged uid, all capabilities dropped, no-new-privileges, memory/pid/file-size/fd limits, an optional
 * stronger runtime (gVisor via algoforge.sandbox.runtime), and a hard container lifetime so a crashed worker
 * cannot leak containers. Each command runs through af-run (sandbox/af-run), which applies the wall-clock limit,
 * measures time and peak memory, and kills anything the program left behind.
 */
public final class Sandbox implements AutoCloseable {
    static final int LIFETIME_SECONDS = 1500;
    // PID 1 reaps orphans and survives af-run's "kill -9 -1"; it exits (removing the container) after LIFETIME_SECONDS.
    private static final String INIT = "end=$((SECONDS+" + LIFETIME_SECONDS + ")); while [ $SECONDS -lt $end ]; do sleep 30 & wait $!; done";

    private final String docker;
    private final String name;

    private Sandbox(String docker, String name) {
        this.docker = docker;
        this.name = name;
    }

    /** Output of one af-run invocation. Memory is unreliable for runs killed at the time limit. */
    public record Exec(int exitCode, double wallSeconds, long maxRssKb, String stdout, String stderr, long stdoutBytes) {}

    public static Sandbox start(AppProperties.Sandbox cfg, String image, int memoryMb) {
        String name = "af-" + UUID.randomUUID();
        List<String> cmd = new ArrayList<>(List.of(cfg.docker(), "run", "-d", "--rm", "--name", name,
                "--label", "algoforge.sandbox=1", "--network", "none",
                "--memory", memoryMb + "m", "--memory-swap", memoryMb + "m", "--cpus", "1",
                "--pids-limit", String.valueOf(cfg.pidsLimit()), "--read-only",
                "--tmpfs", "/tmp:rw,exec,nosuid,nodev,size=64m,mode=1777",
                "--tmpfs", "/box:rw,exec,nosuid,nodev,size=128m,uid=65534,gid=65534",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "65534:65534",
                "--ulimit", "nofile=256:256", "--ulimit", "core=0", "--workdir", "/box"));
        if (cfg.runtime() != null && !cfg.runtime().isBlank()) cmd.addAll(List.of("--runtime", cfg.runtime()));
        cmd.addAll(List.of(image, "bash", "-c", INIT));
        Proc.Result r = Proc.run(cmd, null, Duration.ofSeconds(60));
        if (r.exit() != 0) throw new SandboxException("docker run failed: " + r.stderr().strip());
        return new Sandbox(cfg.docker(), name);
    }

    /** Images this Docker daemon does not have (an unreachable daemon reports all of them). */
    public static List<String> missingImages(AppProperties.Sandbox cfg) {
        return cfg.images().values().stream().distinct()
                .filter(img -> Proc.run(List.of(cfg.docker(), "image", "inspect", "--format", "{{.Id}}", img), null, Duration.ofSeconds(30)).exit() != 0)
                .toList();
    }

    /** Writes a file into /box. Names are fixed by the caller, never user-controlled. */
    public void write(String fileName, String content) {
        if (!fileName.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("bad file name " + fileName);
        Proc.Result r = Proc.run(List.of(docker, "exec", "-i", name, "sh", "-c", "cat > /box/" + fileName),
                content.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60));
        if (r.exit() != 0) throw new SandboxException("writing " + fileName + " failed: " + r.stderr().strip());
    }

    /** Runs a command under af-run with the given wall-clock limit and file-size limit. */
    public Exec exec(double timeLimitSeconds, int fileLimitKb, String stdin, List<String> command) {
        List<String> cmd = new ArrayList<>(List.of(docker, "exec", "-i", name, "af-run",
                String.format(Locale.ROOT, "%.2f", timeLimitSeconds), String.valueOf(fileLimitKb)));
        cmd.addAll(command);
        Proc.Result r = Proc.run(cmd, stdin == null ? new byte[0] : stdin.getBytes(StandardCharsets.UTF_8),
                Duration.ofMillis((long) (timeLimitSeconds * 1000) + 30_000));
        return parse(r);
    }

    /** af-run frame: "exit wall maxrss outBytes errBytes\n" + stderr bytes + stdout bytes. */
    static Exec parse(Proc.Result r) {
        byte[] b = r.stdout();
        int nl = indexOf(b, (byte) '\n');
        String[] head = nl < 0 ? new String[0] : new String(b, 0, nl, StandardCharsets.US_ASCII).trim().split(" ");
        if (head.length != 5) {
            throw new SandboxException("sandbox did not return a result (docker exit " + r.exit() + "): " + r.stderr().strip());
        }
        try {
            int exit = Integer.parseInt(head[0]);
            double wall = Double.parseDouble(head[1]);
            long rss = Long.parseLong(head[2]);
            int outLen = Integer.parseInt(head[3]);
            int errLen = Integer.parseInt(head[4]);
            int start = nl + 1;
            String err = new String(Arrays.copyOfRange(b, start, start + errLen), StandardCharsets.UTF_8);
            String out = new String(Arrays.copyOfRange(b, start + errLen, start + errLen + outLen), StandardCharsets.UTF_8);
            return new Exec(exit, wall, rss, out, err, outLen);
        } catch (RuntimeException e) {
            throw new SandboxException("malformed sandbox result header: " + String.join(" ", head), e);
        }
    }

    /** First line of the language runtime's version banner, recorded with every result. */
    public String version(Language lang) {
        Exec e = exec(20, 64, "", List.of("bash", "-c", lang.versionCommand));
        return e.stdout().strip();
    }

    @Override
    public void close() {
        try {
            Proc.run(List.of(docker, "rm", "-f", name), null, Duration.ofSeconds(30));
        } catch (SandboxException ignored) {
            // the container removes itself after LIFETIME_SECONDS anyway
        }
    }

    private static int indexOf(byte[] b, byte v) {
        for (int i = 0; i < b.length; i++) if (b[i] == v) return i;
        return -1;
    }
}
