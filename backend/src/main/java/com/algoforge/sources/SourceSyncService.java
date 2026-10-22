package com.algoforge.sources;

import com.algoforge.common.ApiException;
import com.algoforge.sources.ProblemSourceAdapter.ExternalProblem;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!worker")
public class SourceSyncService {
    private static final Logger log = LoggerFactory.getLogger(SourceSyncService.class);

    /** Platform tag -> AlgoForge concept, so synced problems join the learning graph. Unmapped tags stay plain tags. */
    static final Map<String, String> TAG_CONCEPTS = Map.ofEntries(
            Map.entry("binary search", "binary-search"), Map.entry("dp", "dp"), Map.entry("greedy", "greedy"),
            Map.entry("dsu", "dsu"), Map.entry("sortings", "sorting"), Map.entry("two pointers", "two-pointers"),
            Map.entry("hashing", "hashing"), Map.entry("bitmasks", "bit-manipulation"),
            Map.entry("number theory", "number-theory"), Map.entry("trees", "trees"),
            Map.entry("shortest paths", "shortest-paths"), Map.entry("dfs and similar", "graph-traversal"),
            Map.entry("graphs", "graph-traversal"), Map.entry("data structures", "segment-trees"));

    private final SourceRegistry registry;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public SourceSyncService(SourceRegistry registry, JdbcClient jdbc, TransactionTemplate tx) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Starts a background sync; progress is read from problem_sources (status RUNNING, then OK or FAILED). */
    public void start(String code) {
        ProblemSourceAdapter adapter = registry.get(code).orElseThrow(() -> ApiException.notFound("Source"));
        if (!adapter.capabilities().metadataSync()) throw ApiException.badRequest(code + " does not support catalogue sync");
        int claimed = jdbc.sql("update problem_sources set last_sync_status = 'RUNNING', last_sync_message = null where code = ? and last_sync_status <> 'RUNNING'")
                .param(code).update();
        if (claimed == 0) throw ApiException.conflict(code + " is already syncing");
        Thread.ofVirtual().name("sync-" + code).start(() -> sync(adapter));
    }

    void sync(ProblemSourceAdapter adapter) {
        try {
            List<ExternalProblem> problems = adapter.fetchCatalogue();
            tx.executeWithoutResult(s -> problems.forEach(p -> upsert(adapter, p)));
            jdbc.sql("""
                    update problem_sources set last_sync_at = now(), last_sync_status = 'OK', last_sync_message = null,
                           last_sync_count = ? where code = ?""").params(problems.size(), adapter.code()).update();
        } catch (Exception e) {
            log.warn("Sync of {} failed", adapter.code(), e);
            jdbc.sql("update problem_sources set last_sync_status = 'FAILED', last_sync_message = ? where code = ?")
                    .params(truncate(e.getMessage()), adapter.code()).update();
        }
    }

    /** A sync cut short by a restart would otherwise look like it is still running. */
    @EventListener(ApplicationReadyEvent.class)
    public void clearInterruptedSyncs() {
        jdbc.sql("update problem_sources set last_sync_status = 'FAILED', last_sync_message = 'Interrupted by a restart' where last_sync_status = 'RUNNING'")
                .update();
    }

    private void upsert(ProblemSourceAdapter adapter, ExternalProblem p) {
        long id = jdbc.sql("""
                insert into problems (source, external_id, canonical_url, title, source_rating, difficulty)
                values (?, ?, ?, ?, ?, ?)
                on conflict (source, external_id, (coalesce(owner_id, 0))) where external_id is not null
                do update set title = excluded.title, source_rating = excluded.source_rating,
                              difficulty = excluded.difficulty, updated_at = now()
                returning id""")
                .params(adapter.code(), p.externalId(), adapter.canonicalUrl(p.externalId()), truncate(p.title()),
                        p.rating(), difficultyFor(p.rating()))
                .query(Long.class).single();
        jdbc.sql("delete from problem_tags where problem_id = ?").param(id).update();
        for (String tag : p.tags()) {
            jdbc.sql("insert into problem_tags (problem_id, tag) values (?, ?) on conflict do nothing").params(id, tag).update();
            String concept = TAG_CONCEPTS.get(tag);
            if (concept != null) {
                jdbc.sql("insert into problem_concepts (problem_id, concept_code, tier) values (?, ?, 2) on conflict do nothing")
                        .params(id, concept).update();
            }
        }
    }

    static String difficultyFor(Integer rating) {
        if (rating == null) return null;
        return rating < 1300 ? "EASY" : rating < 1900 ? "MEDIUM" : "HARD";
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 200 ? s : s.substring(0, 200);
    }
}
