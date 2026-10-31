package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algoforge.AppProperties;
import com.algoforge.ai.AiService;
import com.algoforge.eval.EvaluationService;
import com.algoforge.judge.JobQueue;
import com.algoforge.judge.JudgeService;
import com.algoforge.judge.WorkerHarness;
import com.algoforge.problems.ProblemService;
import com.algoforge.problems.TwinService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Original twins: request guards, the worker's error message, and (with the sandbox images) a full verified build. */
class TwinIntegrationTest extends IntegrationTest {
    @Autowired JobQueue queue;
    @Autowired JudgeService judge;
    @Autowired EvaluationService evaluations;
    @Autowired TwinService twins;
    @Autowired ProblemService problems;
    @Autowired AppProperties props;
    @Autowired TransactionTemplate tx;

    static final String REFERENCE = "import sys\nd=sys.stdin.read().split()\nn=int(d[0])\nprint(sum(int(x) for x in d[1:1+n]))\n";
    static final String BRUTE = "import sys\nd=sys.stdin.read().split()\nt=0\nfor i in range(int(d[0])):\n    t+=int(d[1+i])\nprint(t)\n";
    static final String GEN = "import sys, random\nr=random.Random(int(sys.argv[1]))\nn=int(sys.argv[2])\nb=10 if n<10 else 10**9\n"
            + "print(n)\nprint(' '.join(str(r.randint(-b,b)) for _ in range(n)))\n";
    static final String VALIDATOR = "import sys\nd=sys.stdin.read().split()\nn=int(d[0])\n"
            + "assert 1 <= n <= 100000 and len(d) == n + 1, 'need n then n numbers'\nassert all(abs(int(x)) <= 10**9 for x in d[1:]), 'a_i out of range'\n";

    /** A valid twin reply, with some fields replaced. */
    String twinReply(Map<String, Object> changes) throws Exception {
        Map<String, Object> reply = new HashMap<>(Map.of("title", "Lantern Ledger",
                "statement_md", "Each lantern adds its charge to the ledger.\n\n### Input\nn, then n integers.\n\n### Output\nThe ledger total.",
                "constraints_md", "1 ≤ n ≤ 10^5; |a_i| ≤ 10^9", "reference_py", REFERENCE, "brute_py", BRUTE, "generator_py", GEN,
                "samples", List.of("3\n1 2 3\n", "1\n-5\n"), "hints", List.of("h1", "h2", "h3"), "editorial_md", "One pass.", "max_n", 100000));
        reply.put("validator_py", VALIDATOR);
        reply.putAll(changes);
        return json.writeValueAsString(reply);
    }

    TwinService scripted(String... replies) {
        String provider = jdbc.sql("select provider from ai_provider_configs order by provider").query(String.class).list().getFirst();
        var ai = new AiService(jdbc, json, new AppProperties(null, null, null, new AppProperties.Ai("k", "k", false, 5, 20, 30), null),
                List.of(new AiServiceIntegrationTest.Scripted(provider, (Object[]) replies)));
        return new TwinService(jdbc, queue, problems, ai, props, json, tx);
    }

    long link(Cookie s) throws Exception {
        return body(mvc.perform(jsonPost("/api/problems/link", s, Map.of("url", "https://example.org/puzzles/" + System.nanoTime()))).andReturn())
                .get("id").asLong();
    }

    JobQueue.Job job(long uid, long problem) {
        return new JobQueue.Job(System.nanoTime(), "TWIN", uid, null, "{\"problemId\": " + problem + "}", 1);
    }

    static void assumeSandbox() throws Exception {
        Process p = new ProcessBuilder("docker", "image", "inspect", "algoforge-sandbox-python:1").start();
        assumeTrue(p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0, "python sandbox image is not built");
    }

    @Test
    void requestsAreGuarded() throws Exception {
        Cookie alice = register("twin"), bob = register("twin2");
        long uid = userId(alice), link = link(alice);
        mvc.perform(jsonPost("/api/problems/" + problemId("Range Totals") + "/twin", alice, Map.of())).andExpect(status().isBadRequest());
        mvc.perform(jsonPost("/api/problems/" + link + "/twin", bob, Map.of())).andExpect(status().isNotFound());
        mvc.perform(jsonPost("/api/problems/" + link + "/twin", alice, Map.of())).andExpect(status().isBadRequest());

        TwinService svc = scripted();
        assertThatThrownBy(() -> svc.request(uid, link, new TwinService.Request(null, null))).hasMessageContaining("no topics");
        assertThatThrownBy(() -> svc.request(uid, link, new TwinService.Request("nope", null))).hasMessageContaining("Unknown concept");
        Object jobId = svc.request(uid, link, new TwinService.Request("arrays", "EASY")).get("jobId");
        assertThat(svc.request(uid, link, new TwinService.Request("arrays", null))).containsEntry("jobId", jobId);  // no second job

        // The worker shows why a twin failed instead of a generic internal error.
        var queued = new JobQueue.Job((Long) jobId, "TWIN", uid, null, "{\"problemId\": " + link + "}", 1);
        new WorkerHarness(queue, judge, evaluations, twins).handle(queued);
        assertThat(jdbc.sql("select error from execution_jobs where id = ?").param(jobId).query(String.class).single())
                .contains("need an AI provider");
    }

    @Test
    void aVerifiedTwinIsJudgedLikeAnOriginal() throws Exception {
        assumeSandbox();
        Cookie s = register("twin3");
        long uid = userId(s), link = link(s);
        jdbc.sql("insert into problem_concepts (problem_id, concept_code, tier) values (?, 'arrays', 1)").param(link).update();

        long twin = ((Number) scripted(twinReply(Map.of())).build(job(uid, link)).get("problemId")).longValue();
        assertThat(jdbc.sql("select count(*) from problems where id = ? and owner_id = ? and twin_of = ? and source = 'ORIGINAL'")
                .params(twin, uid, link).query(Long.class).single()).isEqualTo(1);
        JsonNode detail = body(mvc.perform(get("/api/problems/" + twin).cookie(s)).andReturn());
        assertThat(detail.get("judgeable").asBoolean()).isTrue();
        assertThat(jdbc.sql("select validator_py from problems where id = ?").param(twin).query(String.class).single()).isEqualTo(VALIDATOR);
        assertThat(detail.get("samples")).hasSize(2);
        assertThat(detail.get("officialTests").asInt()).isEqualTo(9);
        assertThat(detail.get("twinOfId").asLong()).isEqualTo(link);
        assertThat(detail.get("concepts").get(0).get("code").asText()).isEqualTo("arrays");
        assertThat(body(mvc.perform(get("/api/problems/" + link).cookie(s)).andReturn()).get("twinId").asLong()).isEqualTo(twin);
        // A second request reuses the twin without asking the AI again (the script is empty).
        assertThat(scripted().build(job(uid, link))).containsEntry("problemId", twin);

        assertThat(judged(s, twin, REFERENCE)).isEqualTo("ACCEPTED");
        assertThat(judged(s, twin, "print(0)\n")).isEqualTo("WRONG_ANSWER");
    }

    @Test
    void aBruteForceThatDisagreesStopsTheTwin() throws Exception {
        assumeSandbox();
        Cookie s = register("twin4");
        long uid = userId(s), link = link(s);
        jdbc.sql("insert into problem_concepts (problem_id, concept_code, tier) values (?, 'arrays', 1)").param(link).update();
        String bad = BRUTE.replace("t+=int(d[1+i])", "t+=abs(int(d[1+i]))");
        assertThatThrownBy(() -> scripted(twinReply(Map.of("brute_py", bad)), twinReply(Map.of("brute_py", bad))).build(job(uid, link)))
                .isInstanceOf(TwinService.TwinFailed.class).hasMessageContaining("disagree");
        assertThat(jdbc.sql("select count(*) from problems where twin_of = ?").param(link).query(Long.class).single()).isZero();
    }

    @Test
    void aGeneratorThatBreaksTheConstraintsStopsTheTwin() throws Exception {
        assumeSandbox();
        Cookie s = register("twin5");
        long uid = userId(s), link = link(s);
        jdbc.sql("insert into problem_concepts (problem_id, concept_code, tier) values (?, 'arrays', 1)").param(link).update();
        String strict = twinReply(Map.of("validator_py", VALIDATOR.replace("n <= 100000", "n <= 5")));  // the generator also makes n = 8
        assertThatThrownBy(() -> scripted(strict, strict).build(job(uid, link)))
                .isInstanceOf(TwinService.TwinFailed.class).hasMessageContaining("validator rejects").hasMessageContaining("need n then n numbers");
        assertThat(jdbc.sql("select count(*) from problems where twin_of = ?").param(link).query(Long.class).single()).isZero();
    }

    String judged(Cookie s, long problem, String code) throws Exception {
        var req = new HashMap<String, Object>(Map.of("problemId", problem, "language", "python", "code", code));
        long sid = body(mvc.perform(jsonPost("/api/submissions", s, req)).andReturn()).get("submissionId").asLong();
        WorkerHarness worker = new WorkerHarness(queue, judge, evaluations, twins);
        while (worker.runOne()) { /* drain */ }
        return body(mvc.perform(get("/api/submissions/" + sid).cookie(s)).andReturn()).get("status").asText();
    }
}
