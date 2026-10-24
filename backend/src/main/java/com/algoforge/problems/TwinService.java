package com.algoforge.problems;

import com.algoforge.AppProperties;
import com.algoforge.ai.AiOutputs.Twin;
import com.algoforge.ai.AiService;
import com.algoforge.common.ApiException;
import com.algoforge.judge.JobQueue;
import com.algoforge.judge.Oracle;
import com.algoforge.judge.Verdicts;
import com.algoforge.problems.ProblemService.JudgeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Original twins. For a linked problem (statement on another site) the AI writes a new problem that tests the same
 * techniques at the same difficulty, in its own words. It receives only the concepts, tags and difficulty, never the
 * original's title, link or statement, so there is nothing to copy. The worker runs the AI's reference solution and
 * its independent brute force in the sandbox and requires them to agree on the samples and on random small inputs,
 * and the reference to finish in time at full size, before the twin is saved as the user's private problem.
 */
@Service
public class TwinService {
    static final int ATTEMPTS = 2;
    static final int[] SMALL_N = {1, 2, 3, 5, 8};
    static final int SEEDS_PER_SIZE = 3;
    static final double MAX_REFERENCE_SECONDS = 6;  // the Python time limit of a 2 s problem (time factor 3)

    private final JdbcClient jdbc;
    private final JobQueue queue;
    private final ProblemService problems;
    private final AiService ai;
    private final AppProperties props;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public TwinService(JdbcClient jdbc, JobQueue queue, ProblemService problems, AiService ai, AppProperties props, ObjectMapper json,
                       TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.problems = problems;
        this.ai = ai;
        this.props = props;
        this.json = json;
        this.tx = tx;
    }

    /** concept: used when the link has no concepts of its own; difficulty: overrides the link's. */
    public record Request(@Pattern(regexp = "[a-z0-9-]{1,40}") String concept, @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty) {}

    /** No verified twin could be made; the message is shown to the learner. */
    public static class TwinFailed extends RuntimeException {
        public TwinFailed(String message) { super(message); }
    }

    record Concept(String code, String name, String description) {}

    record Test(String input, String output, Long seed, Integer n) {}

    record Verified(List<Test> samples, List<Test> hidden, String generatorVersion) {}

    // ---- API side ----------------------------------------------------------------------------------------------------

    /** Returns {problemId} when the user already has a twin, otherwise {jobId} of the (new or running) TWIN job. */
    public Map<String, Object> request(long userId, long problemId, Request r) {
        problems.visible(userId, problemId);
        if (problems.judgeable(problemId)) throw ApiException.badRequest("This problem is already judged here");
        Optional<Long> twin = existing(userId, problemId);
        if (twin.isPresent()) return Map.of("problemId", twin.get());
        Optional<Long> running = jdbc.sql("""
                select id from execution_jobs where type = 'TWIN' and user_id = ? and status in ('QUEUED', 'RUNNING')
                   and (payload->>'problemId')::bigint = ? order by id desc limit 1""")
                .params(userId, problemId).query(Long.class).optional();
        if (running.isPresent()) return Map.of("jobId", running.get());
        if (!ai.anyAvailable("TESTGEN")) throw ApiException.badRequest("Original twins need an AI provider; see Settings → AI providers");
        if (r.concept() != null && concept(r.concept()).isEmpty()) throw ApiException.badRequest("Unknown concept " + r.concept());
        if (r.concept() == null && concepts(problemId).isEmpty()) throw ApiException.badRequest("This link has no topics yet; pick a concept for its twin");
        Map<String, Object> payload = new HashMap<>(Map.of("problemId", problemId));
        if (r.concept() != null) payload.put("concept", r.concept());
        if (r.difficulty() != null) payload.put("difficulty", r.difficulty());
        return Map.of("jobId", queue.enqueue("TWIN", userId, null, payload));
    }

    // ---- worker side -------------------------------------------------------------------------------------------------

    public Map<String, Object> build(JobQueue.Job job) throws Exception {
        JsonNode payload = json.readTree(job.payload());
        long sourceId = payload.get("problemId").asLong();
        long userId = job.userId();
        Optional<Long> done = existing(userId, sourceId);  // a re-run after a worker restart must not make a second twin
        if (done.isPresent()) return Map.of("problemId", done.get());

        List<Concept> techniques = new ArrayList<>(concepts(sourceId));
        String chosen = payload.path("concept").asText(null);
        if (chosen != null) concept(chosen).ifPresent(techniques::add);
        record Src(String difficulty, Integer sourceRating) {}
        Src src = jdbc.sql("select difficulty, source_rating from problems where id = ?").param(sourceId).query(Src.class).single();
        String difficulty = payload.path("difficulty").asText(src.difficulty() == null ? "MEDIUM" : src.difficulty());
        // The request number keeps each request out of the AI cache, so asking again after a failure gets a fresh problem.
        String prompt = prompt(techniques, tags(sourceId), difficulty, src.sourceRating()) + "\n\nRequest #" + job.id();

        String feedback = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String p = feedback == null ? prompt : prompt + "\n\n## Your previous attempt failed verification\n" + feedback
                    + "\nWrite a new, corrected problem.";
            var call = ai.twin(userId, p).orElseThrow(() -> new TwinFailed("Original twins need an AI provider; see Settings → AI providers"));
            if (!call.ok()) {
                if (!"INVALID_OUTPUT".equals(call.status())) throw new TwinFailed(call.error());
                feedback = "The reply did not match the required JSON schema.";
                continue;
            }
            try {
                Verified v = verify(call.value());
                return Map.of("problemId", save(userId, sourceId, call.value(), difficulty, chosen, v));
            } catch (TwinFailed e) {
                feedback = e.getMessage();
            }
        }
        throw new TwinFailed("Couldn't verify an original twin after " + ATTEMPTS + " attempts. Last problem: " + feedback);
    }

    Verified verify(Twin t) {
        AppProperties.Sandbox cfg = props.sandbox();
        try (Oracle ref = Oracle.open(cfg, info(t.referencePy(), t.generatorPy(), t.maxN()));
             Oracle brute = Oracle.open(cfg, info(t.brutePy(), null, t.maxN()))) {
            List<Test> samples = new ArrayList<>(), hidden = new ArrayList<>();
            for (String in : t.samples()) samples.add(new Test(in, agree(ref, brute, in, "sample"), null, null));
            for (int n : SMALL_N) {
                for (int k = 0; k < SEEDS_PER_SIZE; k++) {
                    long seed = 100 + n * 10L + k;
                    int size = Math.min(n, t.maxN());
                    String in = ref.generate(seed, size);
                    String out = agree(ref, brute, in, "random input (seed " + seed + ", n = " + size + ")");
                    if (k == 0 && n >= 3) hidden.add(new Test(in, out, seed, size));  // a few verified small cases join the hidden tests
                    if (samples.isEmpty() && n >= 3) samples.add(new Test(in, out, null, null));
                }
            }
            int[] sizes = {Math.max(1, t.maxN() / 100), Math.max(1, t.maxN() / 10), Math.max(1, t.maxN() / 3), t.maxN(), t.maxN(), t.maxN()};
            for (int i = 0; i < sizes.length; i++) {
                String in = ref.generate(i + 1, sizes[i]);
                long t0 = System.nanoTime();
                String out = ref.reference(in);
                double seconds = (System.nanoTime() - t0) / 1e9;
                if (seconds > MAX_REFERENCE_SECONDS) {
                    throw new TwinFailed(String.format("The reference solution took %.1f s at n = %d, over the time limit.", seconds, sizes[i]));
                }
                hidden.add(new Test(in, out, (long) i + 1, sizes[i]));
            }
            return new Verified(samples, hidden, ref.generatorVersion());
        } catch (IllegalStateException e) {  // the generator or a solution crashed
            throw new TwinFailed(excerpt(e.getMessage(), 400));
        }
    }

    static String agree(Oracle ref, Oracle brute, String input, String what) {
        String expected = ref.reference(input), other = brute.reference(input);
        if (expected.isBlank()) throw new TwinFailed("The reference printed nothing for a " + what + ".");
        if (!Verdicts.sameTokens(expected, other)) {
            throw new TwinFailed("The reference and the brute force disagree on a " + what + ": input " + excerpt(input, 200)
                    + ", reference printed " + excerpt(expected, 100) + ", brute force printed " + excerpt(other, 100) + ".");
        }
        return expected;
    }

    long save(long userId, long sourceId, Twin t, String difficulty, String chosenConcept, Verified v) throws Exception {
        String specs = json.writeValueAsString(v.hidden().stream().map(h -> Map.of("seed", h.seed(), "n", h.n())).toList());
        String hints = json.writeValueAsString(t.hints());
        int minutes = switch (difficulty) { case "EASY" -> 15; case "HARD" -> 40; default -> 25; };
        return tx.execute(s -> {
            long id = jdbc.sql("""
                    insert into problems (source, title, statement_md, constraints_md, editorial_md, hints, difficulty, est_minutes,
                        time_limit_ms, memory_limit_mb, max_n, input_size_power, reference_py, generator_py, generated_specs, owner_id, twin_of)
                    values ('ORIGINAL', ?, ?, ?, ?, cast(? as jsonb), ?, ?, 2000, 256, ?, 1, ?, ?, cast(? as jsonb), ?, ?) returning id""")
                    .params(t.title(), t.statementMd(), t.constraintsMd(), t.editorialMd(), hints, difficulty, minutes, t.maxN(),
                            t.referencePy(), t.generatorPy(), specs, userId, sourceId)
                    .query(Long.class).single();
            jdbc.sql("insert into problem_concepts (problem_id, concept_code, tier) select ?, concept_code, tier from problem_concepts where problem_id = ?")
                    .params(id, sourceId).update();
            jdbc.sql("insert into problem_tags (problem_id, tag) select ?, tag from problem_tags where problem_id = ?").params(id, sourceId).update();
            if (chosenConcept != null) {
                jdbc.sql("insert into problem_concepts (problem_id, concept_code, tier) values (?, ?, 1) on conflict do nothing").params(id, chosenConcept).update();
            }
            int ordinal = 0;
            for (Test sample : v.samples()) {
                jdbc.sql("""
                        insert into test_cases (problem_id, kind, ordinal, input, expected_output, verification, provenance)
                        values (?, 'SAMPLE', ?, ?, ?, 'REFERENCE', 'sample; answer from the AI-written reference, which a brute force confirmed')""")
                        .params(id, ordinal++, sample.input(), sample.output()).update();
            }
            for (Test h : v.hidden()) {
                jdbc.sql("""
                        insert into test_cases (problem_id, kind, ordinal, input, expected_output, verification, provenance, generator_version, seed)
                        values (?, 'OFFICIAL', ?, ?, ?, 'REFERENCE', ?, ?, ?)""")
                        .params(id, 1000 + h.seed().intValue(), h.input(), h.output(),
                                "generator seed=" + h.seed() + " n=" + h.n() + ", answer from the AI-written reference (cross-checked against a brute force on random small inputs)",
                                v.generatorVersion(), h.seed())
                        .update();
            }
            return id;
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    /** Deliberately leaves out the original's title, link and statement: the twin is written from the technique alone. */
    static String prompt(List<Concept> techniques, List<String> tags, String difficulty, Integer rating) {
        StringBuilder sb = new StringBuilder("## Techniques to test\n");
        techniques.forEach(c -> sb.append("- ").append(c.name()).append(": ").append(c.description()).append("\n"));
        if (!tags.isEmpty()) sb.append("Related tags: ").append(String.join(", ", tags)).append("\n");
        sb.append("\n## Target difficulty\n").append(difficulty);
        if (rating != null) sb.append(" (about ").append(rating).append(" on an 800 to 3500 rating scale)");
        return sb.toString();
    }

    private Optional<Long> existing(long userId, long sourceId) {
        return jdbc.sql("select id from problems where twin_of = ? and owner_id = ?").params(sourceId, userId).query(Long.class).optional();
    }

    private List<Concept> concepts(long problemId) {
        return jdbc.sql("""
                select c.code, c.name, c.description from problem_concepts pc join concepts c on c.code = pc.concept_code
                 where pc.problem_id = ? order by c.sort_order""").param(problemId).query(Concept.class).list();
    }

    private Optional<Concept> concept(String code) {
        return jdbc.sql("select code, name, description from concepts where code = ?").param(code).query(Concept.class).optional();
    }

    private List<String> tags(long problemId) {
        return jdbc.sql("select tag from problem_tags where problem_id = ? order by tag").param(problemId).query(String.class).list();
    }

    private static JudgeInfo info(String referencePy, String generatorPy, int maxN) {
        return new JudgeInfo(0, "twin", "ORIGINAL", 2000, 256, maxN, 1, null, null, referencePy, generatorPy, null, "[]", 1);
    }

    private static String excerpt(String s, int max) {
        if (s == null) return "";
        String one = s.strip().replace("\n", " ⏎ ");
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }
}
