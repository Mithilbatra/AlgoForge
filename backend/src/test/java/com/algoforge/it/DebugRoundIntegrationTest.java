package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algoforge.eval.EvaluationService;
import com.algoforge.judge.JobQueue;
import com.algoforge.judge.JudgeService;
import com.algoforge.judge.WorkerHarness;
import com.algoforge.oa.DebugRoundService;
import com.algoforge.problems.TwinService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Debugging rounds: each round plants new bugs that the tests catch, and fixing them makes the code pass. */
class DebugRoundIntegrationTest extends IntegrationTest {
    @Autowired JobQueue queue;
    @Autowired JudgeService judge;
    @Autowired EvaluationService evaluations;
    @Autowired TwinService twins;
    @Autowired DebugRoundService debugRounds;

    JsonNode newRound(Cookie s) throws Exception {
        long template = -1;
        for (JsonNode d : body(mvc.perform(get("/api/oa/definitions").cookie(s)).andReturn())) {
            if (d.get("isTemplate").asBoolean() && d.path("bugsPerProblem").asInt() > 0) template = d.get("id").asLong();
        }
        return body(mvc.perform(jsonPost("/api/oa/templates/" + template + "/instantiate", s, Map.of())).andExpect(status().isOk()).andReturn());
    }

    JsonNode definition(Cookie s, long id) throws Exception {
        for (JsonNode d : body(mvc.perform(get("/api/oa/definitions").cookie(s)).andReturn())) if (d.get("id").asLong() == id) return d;
        throw new AssertionError("definition " + id + " not listed");
    }

    void runQueuedJobs() {
        WorkerHarness worker = new WorkerHarness(queue, judge, evaluations, twins, debugRounds);
        while (worker.runOne()) { /* drain */ }
    }

    String submit(Cookie s, long problem, long session, String code) throws Exception {
        long sid = body(mvc.perform(jsonPost("/api/submissions", s, Map.of("problemId", problem, "language", "python", "code", code,
                "oaSessionId", session))).andExpect(status().isOk()).andReturn()).get("submissionId").asLong();
        runQueuedJobs();
        return body(mvc.perform(get("/api/submissions/" + sid).cookie(s)).andReturn()).get("status").asText();
    }

    @Test
    void aRoundStartsOnlyOnceItsBugsArePlanted() throws Exception {
        Cookie s = register("debug");
        JsonNode round = newRound(s);
        long id = round.get("id").asLong();
        assertThat(round.get("starter").asText()).isEqualTo("PREPARING");
        assertThat(round.get("bugsPerProblem").asInt()).isEqualTo(2);
        assertThat(round.get("languages").toString()).isEqualTo("[\"python\"]");
        for (JsonNode p : round.get("problems")) {  // only problems whose reference solution can carry the bugs
            assertThat(jdbc.sql("select reference_py from problems where id = ?").param(p.get("problemId").asLong()).query(String.class).single()).isNotBlank();
        }
        mvc.perform(jsonPost("/api/oa/definitions/" + id + "/start", s, Map.of())).andExpect(status().isConflict());
        jdbc.sql("update execution_jobs set status = 'FAILED' where id = ?").param(round.get("starterJobId").asLong()).update();
        assertThat(definition(s, id).get("starter").asText()).isEqualTo("FAILED");
        assertThat(body(mvc.perform(jsonPost("/api/oa/definitions/" + id + "/start", s, Map.of())).andExpect(status().isConflict()).andReturn())
                .get("error").asText()).contains("start a new debugging round");
    }

    @Test
    void plantedBugsAreCaughtByTheTestsAndFixingThemPasses() throws Exception {
        TwinIntegrationTest.assumeSandbox();
        Cookie s = register("debug2");
        long id = newRound(s).get("id").asLong();
        runQueuedJobs();
        assertThat(definition(s, id).get("starter").asText()).isEqualTo("READY");

        record Starter(long problemId, String code, String bugs) {}
        List<Starter> starters = jdbc.sql("select problem_id, code, bugs::text from oa_starter_code where definition_id = ?")
                .param(id).query(Starter.class).list();
        assertThat(starters).hasSize(3);
        for (Starter st : starters) {
            String[] lines = st.code().split("\n", -1);
            Set<Integer> bugLines = new HashSet<>();
            JsonNode bugs = json.readTree(st.bugs());
            assertThat(bugs.size()).isBetween(1, 2);
            for (JsonNode b : bugs) {
                assertThat(bugLines.add(b.get("line").asInt())).isTrue();  // one bug per line
                assertThat(lines[b.get("line").asInt() - 1]).isEqualTo(b.get("buggy").asText());
                assertThat(b.get("original").asText()).isNotEqualTo(b.get("buggy").asText());
            }
        }

        long session = body(mvc.perform(jsonPost("/api/oa/definitions/" + id + "/start", s, Map.of())).andExpect(status().isOk()).andReturn())
                .get("sessionId").asLong();
        JsonNode state = body(mvc.perform(get("/api/oa/sessions/" + session).cookie(s)).andReturn());
        assertThat(state.get("bugsPerProblem").asInt()).isEqualTo(2);
        Starter first = starters.stream().filter(st -> st.problemId() == state.get("problems").get(0).get("problemId").asLong()).findFirst().orElseThrow();
        assertThat(state.get("problems").get(0).get("starterCode").asText()).isEqualTo(first.code());

        assertThat(submit(s, first.problemId(), session, first.code())).isNotEqualTo("ACCEPTED");
        String[] fixed = first.code().split("\n", -1);
        for (JsonNode b : json.readTree(first.bugs())) fixed[b.get("line").asInt() - 1] = b.get("original").asText();
        assertThat(submit(s, first.problemId(), session, String.join("\n", fixed))).isEqualTo("ACCEPTED");

        mvc.perform(jsonPost("/api/oa/sessions/" + session + "/submit", s, Map.of())).andExpect(status().isOk());
        JsonNode report = body(mvc.perform(get("/api/oa/sessions/" + session + "/report").cookie(s)).andExpect(status().isOk()).andReturn()).get("report");
        for (JsonNode p : report.get("problems")) assertThat(p.get("plantedBugs").size()).isBetween(1, 2);
    }
}
