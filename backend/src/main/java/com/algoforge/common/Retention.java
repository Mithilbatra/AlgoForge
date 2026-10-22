package com.algoforge.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retention policy: expired AI cache, old job payloads/results, usage logs and audit entries are pruned nightly. */
@Component
@Profile("!worker")
public class Retention {
    private static final Logger log = LoggerFactory.getLogger(Retention.class);
    private final JdbcClient jdbc;

    public Retention(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Scheduled(cron = "0 30 3 * * *")
    public void prune() {
        int cache = jdbc.sql("delete from ai_cache where expires_at < now()").update();
        int jobs = jdbc.sql("delete from execution_jobs where status in ('DONE', 'FAILED') and finished_at < now() - interval '30 days'").update();
        int usage = jdbc.sql("delete from ai_usage where created_at < now() - interval '180 days'").update();
        int audit = jdbc.sql("delete from audit_log where created_at < now() - interval '365 days'").update();
        log.info("Retention pruned cache={} jobs={} usage={} audit={}", cache, jobs, usage, audit);
    }
}
