package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.Cookie;

/** Assessment lifecycle with judged submissions inserted directly (judging itself is covered by JudgeIntegrationTest). */
class OaIntegrationTest extends IntegrationTest {

    long createAndStart(Cookie s, long p1, long p2, boolean partial, int penalty) throws Exception {
        JsonNode def = body(mvc.perform(jsonPost("/api/oa/definitions", s, Map.of("title", "Mock OA", "durationMinutes", 30,
                "mode", "ASSESSMENT", "partialCredit", partial, "wrongPenaltyPct", penalty, "languages", List.of("python"),
                "allowHints", false, "problems", List.of(Map.of("problemId", p1, "points", 100), Map.of("problemId", p2, "points", 200)))))
                .andExpect(status().isOk()).andReturn());
        return body(mvc.perform(jsonPost("/api/oa/definitions/" + def.get("id").asLong() + "/start", s, Map.of()))
                .andExpect(status().isOk()).andReturn()).get("sessionId").asLong();
    }

    long judged(long uid, long problem, long session, String status, int passed, int total) {
        return jdbc.sql("""
                insert into submissions (user_id, problem_id, oa_session_id, language, code, status, passed, total, judged_at)
                values (?, ?, ?, 'python', 'print(1)', ?, ?, ?, case when ? in ('QUEUED','COMPILING','RUNNING') then null else now() end) returning id""")
                .params(uid, problem, session, status, passed, total, status).query(Long.class).single();
    }

    @Test
    void allowingHintsStillHidesSolutionsDuringTheAssessment() throws Exception {
        Cookie s = register("hints");
        long p = problemId("Stepping Stones");
        JsonNode def = body(mvc.perform(jsonPost("/api/oa/definitions", s, Map.of("title", "Hints on", "durationMinutes", 30,
                "mode", "ASSESSMENT", "partialCredit", true, "wrongPenaltyPct", 0, "languages", List.of("python"),
                "allowHints", true, "problems", List.of(Map.of("problemId", p, "points", 100))))).andExpect(status().isOk()).andReturn());
        mvc.perform(jsonPost("/api/oa/definitions/" + def.get("id").asLong() + "/start", s, Map.of())).andExpect(status().isOk());
        JsonNode detail = body(mvc.perform(get("/api/problems/" + p).cookie(s)).andExpect(status().isOk()).andReturn());
        assertThat(detail.get("hints")).isNotEmpty();
        assertThat(detail.get("editorialAvailable").asBoolean()).isFalse();
        mvc.perform(get("/api/problems/" + p + "/editorial").cookie(s)).andExpect(status().isForbidden());
        // Submitting outside the assessment would show hidden test data, so it stays blocked too.
        mvc.perform(jsonPost("/api/submissions", s, Map.of("problemId", p, "language", "python", "code", "print(1)")))
                .andExpect(status().isConflict());
    }

    @Test
    void expiryFinalisesDeterministicallyAndBlocksLateSubmissions() throws Exception {
        Cookie s = register("oa");
        long uid = userId(s), p1 = problemId("Range Totals"), p2 = problemId("Coin Combinations");
        long sid = createAndStart(s, p1, p2, true, 0);

        mvc.perform(jsonPost("/api/oa/definitions/" + jdbc.sql("select definition_id from oa_sessions where id = ?").param(sid)
                .query(Long.class).single() + "/start", s, Map.of())).andExpect(status().isConflict());
        JsonNode detail = body(mvc.perform(get("/api/problems/" + p1).cookie(s)).andReturn());
        assertThat(detail.get("assessmentLocked").asBoolean()).isTrue();
        assertThat(detail.has("hints")).isFalse();
        mvc.perform(get("/api/problems/" + p1 + "/editorial").cookie(s)).andExpect(status().isForbidden());

        judged(uid, p1, sid, "WRONG_ANSWER", 2, 8);
        judged(uid, p1, sid, "ACCEPTED", 8, 8);
        judged(uid, p2, sid, "WRONG_ANSWER", 3, 6);
        JsonNode live = body(mvc.perform(get("/api/oa/sessions/" + sid).cookie(s)).andReturn());
        assertThat(live.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(live.get("pointsEarned").asDouble()).isEqualTo(200.0);
        assertThat(live.get("remainingMs").asLong()).isBetween(1_000_000L, 1_800_000L);

        jdbc.sql("update oa_sessions set ends_at = now() - interval '1 second' where id = ?").param(sid).update();
        JsonNode done = body(mvc.perform(get("/api/oa/sessions/" + sid).cookie(s)).andReturn());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("endReason").asText()).isEqualTo("EXPIRED");
        assertThat(done.get("remainingMs").asLong()).isZero();

        JsonNode report = body(mvc.perform(get("/api/oa/sessions/" + sid + "/report").cookie(s)).andExpect(status().isOk()).andReturn()).get("report");
        assertThat(report.get("totalScore").asDouble()).isEqualTo(200.0);
        assertThat(report.get("maxScore").asInt()).isEqualTo(300);
        assertThat(report.get("solved").asInt()).isEqualTo(1);
        assertThat(report.get("partiallySolved").asInt()).isEqualTo(1);
        assertThat(report.get("timeline")).hasSize(3);
        assertThat(report.get("conceptsToRevise").toString()).contains("dp");

        mvc.perform(jsonPost("/api/submissions", s, Map.of("problemId", p1, "language", "python", "code", "print(1)", "oaSessionId", sid)))
                .andExpect(status().isConflict());
        // after the assessment the problem's hints and editorial unlock again
        assertThat(body(mvc.perform(get("/api/problems/" + p1).cookie(s)).andReturn()).get("hints")).isNotEmpty();
    }

    @Test
    void finalisationWaitsForSubmissionsStillBeingJudged() throws Exception {
        Cookie s = register("oa2");
        long uid = userId(s), p1 = problemId("Matching Pairs"), p2 = problemId("Stepping Stones");
        long sid = createAndStart(s, p1, p2, false, 10);
        judged(uid, p1, sid, "WRONG_ANSWER", 7, 8);
        long pending = judged(uid, p1, sid, "RUNNING", 0, 0);
        JsonNode after = body(mvc.perform(jsonPost("/api/oa/sessions/" + sid + "/submit", s, Map.of())).andReturn());
        assertThat(after.get("status").asText()).isEqualTo("FINALIZING");
        mvc.perform(get("/api/oa/sessions/" + sid + "/report").cookie(s)).andExpect(status().isConflict());

        jdbc.sql("update submissions set status = 'ACCEPTED', passed = 8, total = 8, judged_at = now() where id = ?").param(pending).update();
        JsonNode report = body(mvc.perform(get("/api/oa/sessions/" + sid + "/report").cookie(s)).andExpect(status().isOk()).andReturn()).get("report");
        assertThat(report.get("endReason").asText()).isEqualTo("SUBMITTED");
        assertThat(report.get("totalScore").asDouble()).isEqualTo(90.0);  // all-or-nothing, minus one 10% penalty
    }

    @Test
    void templatesInstantiateIntoConcreteAssessments() throws Exception {
        Cookie s = register("tpl");
        JsonNode defs = body(mvc.perform(get("/api/oa/definitions").cookie(s)).andReturn());
        long template = 0;
        for (JsonNode d : defs) if (d.get("title").asText().equals("General placement OA")) template = d.get("id").asLong();
        JsonNode inst = body(mvc.perform(jsonPost("/api/oa/templates/" + template + "/instantiate", s, Map.of())).andExpect(status().isOk()).andReturn());
        assertThat(inst.get("problems")).hasSize(3);
        assertThat(inst.get("problems")).extracting(p -> p.get("difficulty").asText()).containsExactly("EASY", "MEDIUM", "HARD");
        assertThat(inst.get("isTemplate").asBoolean()).isFalse();
    }
}
