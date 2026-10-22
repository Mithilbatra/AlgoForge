package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algoforge.AppProperties;
import com.algoforge.ai.AiProvider;
import com.algoforge.ai.AiService;
import com.algoforge.coach.CoachService;
import com.algoforge.problems.ProblemService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Forge Coach: the built-in hint ladder and its guards (the test environment has no keys), plus the AI path with a scripted provider. */
class CoachIntegrationTest extends IntegrationTest {
    @Autowired ProblemService problems;

    JsonNode ask(Cookie s, long problem, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonPost("/api/problems/" + problem + "/coach", s, body)).andExpect(status().isOk()).andReturn());
    }

    boolean revealed(long uid, long problem) {
        return jdbc.sql("select coalesce(bool_or(revealed_at is not null), false) from user_problem_state where user_id = ? and problem_id = ?")
                .params(uid, problem).query(Boolean.class).single();
    }

    @Test
    void builtInCoachClimbsTheHintLadder() throws Exception {
        Cookie s = register("coach");
        long p = problemId("Range Totals");
        JsonNode one = ask(s, p, Map.of("level", 1));
        assertThat(one.get("source").asText()).isEqualTo("built-in");
        assertThat(one.get("reply").asText()).contains("Hint 1 of 3");
        assertThat(ask(s, p, Map.of("level", 2)).get("reply").asText()).contains("Hint 2 of 3");
        assertThat(one.get("nextStep").asText()).isNotBlank();
    }

    @Test
    void diagnosesTheLastJudgedFailureAndReadsTheCode() throws Exception {
        Cookie s = register("coach2");
        long uid = userId(s), p = problemId("Range Totals");
        long sid = jdbc.sql("""
                insert into submissions (user_id, problem_id, language, code, status, passed, total, judged_at)
                values (?, ?, 'cpp', 'int main(){}', 'WRONG_ANSWER', 0, 6, now()) returning id""").params(uid, p).query(Long.class).single();
        long sample = jdbc.sql("select id from test_cases where problem_id = ? and kind = 'SAMPLE' order by ordinal limit 1").param(p).query(Long.class).single();
        jdbc.sql("insert into submission_results (submission_id, ordinal, test_case_id, kind, verdict, output_excerpt) values (?, 0, ?, 'SAMPLE', 'WRONG_ANSWER', '0')")
                .params(sid, sample).update();
        String code = "#include <bits/stdc++.h>\nint main(){int n,q;std::cin>>n>>q;for(int i=0;i<q;i++){int s=0;for(int j=0;j<n;j++)s+=j;std::cout<<s<<std::endl;}}\n";
        JsonNode r = ask(s, p, Map.of("level", 1, "code", code, "language", "cpp", "message", "why is it wrong?"));
        assertThat(r.get("reply").asText()).contains("fails a visible sample").contains("Also noticed");
        assertThat(r.get("notice").asText()).contains("Free-form questions need an AI provider");
    }

    @Test
    void onlyAWrittenOutlineCountsAsARevealAndIsAudited() throws Exception {
        Cookie s = register("coach3");
        long uid = userId(s), p = problemId("Stepping Stones");
        // The built-in outline is the authored third hint, which the Hints tab shows freely.
        assertThat(ask(s, p, Map.of("level", 3)).get("source").asText()).isEqualTo("built-in");
        assertThat(revealed(uid, p)).isFalse();

        String provider = jdbc.sql("select provider from ai_provider_configs order by provider").query(String.class).list().getFirst();
        var ai = new AiService(jdbc, json, new AppProperties(null, null, null, new AppProperties.Ai("k", "k", false, 5, 20, 30), null),
                List.of(new AiServiceIntegrationTest.Scripted(provider,
                        "{\"reply\": \"1. Track the cheapest way to stand on each stone.\", \"next_step\": \"Write the recurrence.\"}")));
        var reply = new CoachService(jdbc, problems, ai, json).coach(uid, p, new CoachService.Request(3, null, null, null, null, null));
        assertThat(reply.source()).isEqualTo("ai");
        assertThat(reply.notice()).contains("solution reveal");
        assertThat(revealed(uid, p)).isTrue();
        assertThat(jdbc.sql("select count(*) from audit_log where user_id = ? and action = 'REVEAL_SOLUTION'").param(uid)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void longCoachRepliesCanBeSentBackAsHistory() throws Exception {
        Cookie s = register("coach5");
        var history = List.of(Map.of("role", "user", "text", "help"), Map.of("role", "coach", "text", "x".repeat(3000)));
        assertThat(ask(s, problemId("Range Totals"), Map.of("level", 1, "history", history)).get("source").asText()).isEqualTo("built-in");
    }

    @Test
    void coachIsOffDuringAnAssessmentAndValidatesInput() throws Exception {
        Cookie s = register("coach4");
        long p = problemId("Matching Pairs");
        JsonNode def = body(mvc.perform(jsonPost("/api/oa/definitions", s, Map.of("title", "Locked", "durationMinutes", 30, "mode", "ASSESSMENT",
                "partialCredit", true, "wrongPenaltyPct", 0, "languages", List.of("python"), "allowHints", false,
                "problems", List.of(Map.of("problemId", p, "points", 100))))).andReturn());
        mvc.perform(jsonPost("/api/oa/definitions/" + def.get("id").asLong() + "/start", s, Map.of())).andExpect(status().isOk());
        mvc.perform(jsonPost("/api/problems/" + p + "/coach", s, Map.of("level", 1))).andExpect(status().isForbidden());
        mvc.perform(jsonPost("/api/problems/" + problemId("Range Totals") + "/coach", s, Map.of("level", 7))).andExpect(status().isBadRequest());
    }

    @Test
    void duringAnAssessmentThatAllowsHintsTheAiNeverSeesTestData() throws Exception {
        Cookie s = register("coach6");
        long uid = userId(s), p = problemId("Range Totals");
        JsonNode def = body(mvc.perform(jsonPost("/api/oa/definitions", s, Map.of("title", "Hints on", "durationMinutes", 30, "mode", "ASSESSMENT",
                "partialCredit", true, "wrongPenaltyPct", 0, "languages", List.of("python"), "allowHints", true,
                "problems", List.of(Map.of("problemId", p, "points", 100))))).andReturn());
        long session = body(mvc.perform(jsonPost("/api/oa/definitions/" + def.get("id").asLong() + "/start", s, Map.of()))
                .andExpect(status().isOk()).andReturn()).get("sessionId").asLong();
        long sid = jdbc.sql("""
                insert into submissions (user_id, problem_id, oa_session_id, language, code, status, passed, total, judged_at)
                values (?, ?, ?, 'python', 'print(0)', 'WRONG_ANSWER', 2, 6, now()) returning id""").params(uid, p, session).query(Long.class).single();
        // A private (GENERATED) test, so the shared problem's judged tests stay untouched.
        long test = jdbc.sql("""
                insert into test_cases (problem_id, kind, ordinal, input, expected_output, verification, provenance, owner_id)
                values (?, 'GENERATED', 0, 'HIDDEN-INPUT', 'HIDDEN-EXPECTED', 'AUTHOR', 'test', ?) returning id""").params(p, uid).query(Long.class).single();
        jdbc.sql("insert into submission_results (submission_id, ordinal, test_case_id, kind, verdict, output_excerpt) values (?, 0, ?, 'OFFICIAL', 'WRONG_ANSWER', 'HIDDEN-OUTPUT')")
                .params(sid, test).update();

        String provider = jdbc.sql("select provider from ai_provider_configs order by provider").query(String.class).list().getFirst();
        List<String> prompts = new ArrayList<>();
        AiProvider capture = new AiProvider() {
            @Override public String name() { return provider; }
            @Override public boolean configured() { return true; }
            @Override public Completion complete(String model, String system, String user, JsonNode schema, int maxOutputTokens) {
                prompts.add(user);
                return new Completion("{\"reply\": \"Check the range boundaries.\", \"next_step\": \"Trace a small case.\"}", model, 1, 1);
            }
        };
        var ai = new AiService(jdbc, json, new AppProperties(null, null, null, new AppProperties.Ai("k", "k", false, 5, 20, 30), null), List.of(capture));
        var reply = new CoachService(jdbc, problems, ai, json).coach(uid, p, new CoachService.Request(1, null, null, null, null, null));
        assertThat(reply.source()).isEqualTo("ai");
        assertThat(prompts.getFirst()).contains("WRONG_ANSWER").doesNotContain("HIDDEN-INPUT").doesNotContain("HIDDEN-EXPECTED").doesNotContain("HIDDEN-OUTPUT");
    }
}
