package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.judge.JobQueue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Postgres job queue: what finished jobs keep. */
class JobQueueIntegrationTest extends IntegrationTest {
    @Autowired JobQueue queue;

    String column(String name, long id) {
        return jdbc.sql("select " + name + "::text from execution_jobs where id = ?").param(id).query(String.class).single();
    }

    @Test
    void finishedJobsKeepTheirResultButNotTheirPayload() throws Exception {
        long uid = userId(register("jobs"));
        long done = queue.enqueue("RUN", uid, null, Map.of("code", "print(1)", "cases", List.of(Map.of("input", "7".repeat(100_000)))));
        long failed = queue.enqueue("RUN", uid, null, Map.of("code", "print(1)"));
        long stale = queue.enqueue("RUN", uid, null, Map.of("code", "print(1)"));
        jdbc.sql("update execution_jobs set status = 'RUNNING', attempts = 2, locked_at = now() - interval '1 hour' where id = ?").param(stale).update();

        queue.complete(done, Map.of("runs", List.of()));
        queue.fail(failed, "Execution environment error");
        queue.recoverStale(300, 2);

        for (long id : new long[] {done, failed, stale}) assertThat(column("payload", id)).isEqualTo("{}");
        assertThat(column("result", done)).contains("runs");
        assertThat(column("status", stale)).isEqualTo("FAILED");
    }
}
