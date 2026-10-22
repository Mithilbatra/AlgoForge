package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Forge Coach without an AI provider (the test environment has no keys): the built-in hint ladder and its guards. */
class CoachIntegrationTest extends IntegrationTest {

    JsonNode ask(Cookie s, long problem, Map<String, Object> body) throws Exception {
        return body(mvc.perform(jsonPost("/api/problems/" + problem + "/coach", s, body)).andExpect(status().isOk()).andReturn());
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
    void outlinesCountAsASolutionReveal() throws Exception {
        Cookie s = register("coach3");
        long p = problemId("Stepping Stones");
        JsonNode r = ask(s, p, Map.of("level", 3));
        assertThat(r.get("notice").asText()).contains("solution reveal");
        assertThat(jdbc.sql("select revealed_at is not null from user_problem_state where user_id = ? and problem_id = ?")
                .params(userId(s), p).query(Boolean.class).single()).isTrue();
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
}
