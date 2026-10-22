package com.algoforge.judge;

import com.algoforge.eval.EvaluationService;

/** Test helper: runs queued jobs synchronously through the real worker handler. */
public final class WorkerHarness {
    private final JobQueue queue;
    private final Worker worker;

    public WorkerHarness(JobQueue queue, JudgeService judge, EvaluationService evaluations) {
        this.queue = queue;
        this.worker = new Worker(queue, judge, evaluations, new com.algoforge.AppProperties(
                new com.algoforge.AppProperties.Worker(1, 100, 300, 2), null, null, null, null));
    }

    public boolean runOne() {
        var job = queue.claim("test-harness");
        job.ifPresent(worker::handle);
        return job.isPresent();
    }
}
