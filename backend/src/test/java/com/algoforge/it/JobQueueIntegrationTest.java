package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algoforge.common.ApiException;
import com.algoforge.judge.JobQueue;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Postgres job queue: what finished jobs keep, how much one user may queue, and which jobs a worker claims. */
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

    @Test
    void oneUserCannotQueueMoreThanFiveJobs() throws Exception {
        Cookie s = register("busy");
        long uid = userId(s), other = userId(register("calm"));
        for (int i = 0; i < 5; i++) queue.enqueue("RUN", uid, null, Map.of());
        assertThatThrownBy(() -> queue.enqueue("RUN", uid, null, Map.of())).isInstanceOf(ApiException.class).hasMessageContaining("in progress");
        mvc.perform(jsonPost("/api/runs", s, Map.of("problemId", problemId("Range Totals"), "language", "python", "code", "print(1)",
                "input", "1", "samples", false))).andExpect(status().isTooManyRequests());
        long submissions = jdbc.sql("select count(*) from submissions where user_id = ?").param(uid).query(Long.class).single();
        mvc.perform(jsonPost("/api/submissions", s, Map.of("problemId", problemId("Range Totals"), "language", "python", "code", "print(1)")))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.sql("select count(*) from submissions where user_id = ?").param(uid).query(Long.class).single())
                .isEqualTo(submissions);  // the refused submission is rolled back, not left unjudged
        long judged = jdbc.sql("""
                insert into submissions (user_id, problem_id, language, code, status, passed, total, judged_at)
                values (?, ?, 'python', 'print(1)', 'WRONG_ANSWER', 0, 6, now()) returning id""")
                .params(uid, problemId("Range Totals")).query(Long.class).single();
        jdbc.sql("insert into evaluation_reports (submission_id, correctness, overall_score, dimensions) values (?, 'FAILED', 0, '{}')")
                .param(judged).update();
        mvc.perform(jsonPost("/api/submissions/" + judged + "/evaluate", s, Map.of("useAi", false))).andExpect(status().isTooManyRequests());
        assertThat(jdbc.sql("select status from evaluation_reports where submission_id = ?").param(judged).query(String.class).single())
                .isEqualTo("BASIC");  // not left waiting for a deep evaluation that was never queued
        queue.enqueue("RUN", other, null, Map.of());  // other users are not affected

        jdbc.sql("update execution_jobs set status = 'DONE' where user_id in (?, ?)").params(uid, other).update();  // nothing left for a test worker
        queue.enqueue("RUN", uid, null, Map.of());  // finished jobs free the slots
        jdbc.sql("update execution_jobs set status = 'DONE' where user_id = ?").param(uid).update();
    }

    @Test
    void aWorkerRunsOneSlowJobAtATime() throws Exception {
        jdbc.sql("update execution_jobs set status = 'DONE' where status = 'QUEUED'").update();  // claim() takes the oldest job of any test
        long uid = userId(register("slow"));
        long twin = queue.enqueue("TWIN", uid, null, Map.of());
        long evaluation = queue.enqueue("EVALUATE", uid, null, Map.of());
        long run = queue.enqueue("RUN", uid, null, Map.of());
        assertThat(queue.claim("worker-a").orElseThrow().id()).isEqualTo(twin);
        assertThat(queue.claim("worker-a").orElseThrow().id()).isEqualTo(run);  // the evaluation waits while worker-a runs a twin
        assertThat(queue.claim("worker-b").orElseThrow().id()).isEqualTo(evaluation);
        jdbc.sql("update execution_jobs set status = 'DONE' where user_id = ?").param(uid).update();
    }
}
