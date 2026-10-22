package com.algoforge.judge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Postgres-backed job queue: workers claim with FOR UPDATE SKIP LOCKED, refresh locked_at while busy, and jobs whose
 * worker disappeared are re-queued (or failed after max attempts).
 */
@Component
public class JobQueue {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JobQueue(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Job(long id, String type, Long userId, Long submissionId, String payload, int attempts) {}

    public record Recovered(long id, String type, Long submissionId, String status) {}

    public long enqueue(String type, Long userId, Long submissionId, Object payload) {
        return jdbc.sql("insert into execution_jobs (type, user_id, submission_id, payload) values (?, ?, ?, cast(? as jsonb)) returning id")
                .params(type, userId, submissionId, write(payload)).query(Long.class).single();
    }

    public Optional<Job> claim(String workerId) {
        return jdbc.sql("""
                update execution_jobs set status = 'RUNNING', worker_id = ?, locked_at = now(), attempts = attempts + 1
                 where id = (select id from execution_jobs where status = 'QUEUED' order by id for update skip locked limit 1)
                returning id, type, user_id, submission_id, payload::text, attempts""")
                .param(workerId).query(Job.class).optional();
    }

    public void heartbeat(List<Long> ids) {
        if (ids.isEmpty()) return;
        jdbc.sql("update execution_jobs set locked_at = now() where id = any(?) and status = 'RUNNING'")
                .param(ids.toArray(Long[]::new)).update();
    }

    public void complete(long id, Object result) {
        jdbc.sql("update execution_jobs set status = 'DONE', result = cast(? as jsonb), finished_at = now() where id = ?")
                .params(result == null ? null : write(result), id).update();
    }

    public void fail(long id, String error) {
        jdbc.sql("update execution_jobs set status = 'FAILED', error = ?, finished_at = now() where id = ?")
                .params(error, id).update();
    }

    public List<Recovered> recoverStale(int staleAfterSeconds, int maxAttempts) {
        return jdbc.sql("""
                update execution_jobs
                   set status = case when attempts >= :max then 'FAILED' else 'QUEUED' end,
                       error = case when attempts >= :max then 'Worker stopped while running this job' end,
                       finished_at = case when attempts >= :max then now() end,
                       worker_id = null, locked_at = null
                 where status = 'RUNNING' and locked_at < now() - make_interval(secs => :stale)
                returning id, type, submission_id, status""")
                .param("max", maxAttempts).param("stale", staleAfterSeconds).query(Recovered.class).list();
    }

    String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
