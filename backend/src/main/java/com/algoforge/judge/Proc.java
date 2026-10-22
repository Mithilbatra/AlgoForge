package com.algoforge.judge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs a host command (the docker CLI) with stdin, capturing stdout/stderr concurrently so pipes never deadlock. */
final class Proc {
    private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();

    record Result(int exit, byte[] stdout, String stderr) {}

    static Result run(List<String> cmd, byte[] stdin, Duration timeout) {
        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new SandboxException("cannot start " + cmd.getFirst() + ": " + e.getMessage(), e);
        }
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()), READERS);
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()), READERS);
        try (OutputStream in = p.getOutputStream()) {
            if (stdin != null) in.write(stdin);
        } catch (IOException ignored) {
            // the program exited without reading all of its input; that is the program's business
        }
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new SandboxException("command timed out after " + timeout.toSeconds() + "s: " + cmd.getFirst() + " " + cmd.get(1));
            }
            return new Result(p.exitValue(), out.get(30, TimeUnit.SECONDS), new String(err.get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SandboxException("interrupted", e);
        } catch (Exception e) {
            if (e instanceof SandboxException se) throw se;
            throw new SandboxException("failed reading process output: " + e.getMessage(), e);
        }
    }

    private static byte[] readAll(InputStream s) {
        try (s) {
            return s.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
