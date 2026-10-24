package com.algoforge.judge;

import com.algoforge.AppProperties;
import com.algoforge.eval.EvaluationService;
import com.algoforge.judge.JobQueue.Job;
import com.algoforge.problems.TwinService;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The execution worker process (spring profile "worker"): claims jobs and runs them in sandboxes. */
@Component
@Profile("worker")
public class Worker implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final JobQueue queue;
    private final JudgeService judge;
    private final EvaluationService evaluations;
    private final TwinService twins;
    private final AppProperties.Worker cfg;
    private final AppProperties.Sandbox sandbox;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean stopping;

    public Worker(JobQueue queue, JudgeService judge, EvaluationService evaluations, TwinService twins, AppProperties props) {
        this.queue = queue;
        this.judge = judge;
        this.evaluations = evaluations;
        this.twins = twins;
        this.cfg = props.worker();
        this.sandbox = props.sandbox();
    }

    @Override
    public void run(ApplicationArguments args) {
        // A worker that cannot start sandboxes would claim jobs and fail every one, so it refuses to start instead.
        List<String> missing = Sandbox.missingImages(sandbox);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Sandbox images " + missing + " are not available from this worker's Docker daemon. "
                    + "Run sandbox/build.sh against the same Docker context (DOCKER_HOST) the worker uses.");
        }
        housekeeping.scheduleWithFixedDelay(this::housekeep, 0, 30, TimeUnit.SECONDS);
        for (int i = 0; i < cfg.threads(); i++) {
            Thread.ofPlatform().name("worker-" + i).start(this::loop);
        }
        log.info("Worker {} started with {} threads", workerId, cfg.threads());
    }

    private void loop() {
        while (!stopping) {
            try {
                var job = queue.claim(workerId);
                if (job.isEmpty()) {
                    Thread.sleep(cfg.pollIntervalMs());
                    continue;
                }
                handle(job.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Worker loop error (database unavailable?)", e);
                sleepQuietly(5000);
            }
        }
    }

    void handle(Job job) {
        running.add(job.id());
        try {
            Object result = switch (job.type()) {
                case "JUDGE" -> { judge.judge(job); yield null; }
                case "RUN" -> judge.run(job);
                case "EVALUATE" -> { evaluations.deepEvaluate(job); yield null; }
                case "TWIN" -> twins.build(job);
                default -> throw new IllegalStateException("unknown job type " + job.type());
            };
            queue.complete(job.id(), result);
        } catch (Exception e) {
            log.error("Job {} ({}) failed", job.id(), job.type(), e);
            String msg = e instanceof SandboxException ? "Execution environment error: " + e.getMessage()
                    : e instanceof TwinService.TwinFailed ? e.getMessage() : "Internal error while processing";
            queue.fail(job.id(), msg);
            if ("JUDGE".equals(job.type()) && job.submissionId() != null) judge.markInternalError(job.submissionId(), msg);
            if ("EVALUATE".equals(job.type()) && job.submissionId() != null) evaluations.markDeepFailed(job.submissionId(), msg);
        } finally {
            running.remove(job.id());
        }
    }

    private void housekeep() {
        try {
            queue.heartbeat(List.copyOf(running));
            for (JobQueue.Recovered r : queue.recoverStale(cfg.staleAfterSeconds(), cfg.maxAttempts())) {
                log.warn("Recovered stale job {} ({}) -> {}", r.id(), r.type(), r.status());
                if ("FAILED".equals(r.status()) && r.submissionId() != null) {
                    if ("JUDGE".equals(r.type())) judge.markInternalError(r.submissionId(), "The worker stopped while judging; please resubmit");
                    if ("EVALUATE".equals(r.type())) evaluations.markDeepFailed(r.submissionId(), "The worker stopped during evaluation");
                }
            }
        } catch (Exception e) {
            log.error("Worker housekeeping failed", e);
        }
    }

    @PreDestroy
    void stop() {
        stopping = true;
        housekeeping.shutdownNow();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
