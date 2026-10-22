package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algoforge.judge.JobQueue;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;

class ApiIntegrationTest extends IntegrationTest {
    @Autowired JobQueue queue;
    @Autowired com.algoforge.judge.JudgeService judge;

    @Test
    void apiRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/problems")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.c\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void registerLoginAndDuplicateEmail() throws Exception {
        String email = "Case" + System.nanoTime() + "@Example.com";
        var reg = Map.of("email", email, "password", "correct horse battery", "displayName", "Ada");
        mvc.perform(post("/api/auth/register").with(csrf()).with(r -> { r.setRemoteAddr("10.9.0.1"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(reg))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/register").with(csrf()).with(r -> { r.setRemoteAddr("10.9.0.2"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email.toLowerCase(),
                        "password", "another long password", "displayName", "Eve")))).andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "wrong password")))).andExpect(status().isUnauthorized());
        var ok = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email.toUpperCase(), "password", "correct horse battery")))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("displayName").asText()).isEqualTo("Ada");
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bad\",\"password\":\"short\",\"displayName\":\"\"}")).andExpect(status().isBadRequest());
        // BCrypt reads at most 72 bytes: longer passwords (by length, or by bytes when accented) are refused instead of crashing.
        for (String pw : List.of("x".repeat(100), "é".repeat(40))) {
            mvc.perform(post("/api/auth/register").with(csrf()).with(r -> { r.setRemoteAddr("10.9.0.3"); return r; })
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                            "email", "long" + System.nanoTime() + "@example.com", "password", pw, "displayName", "Long"))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void loginAttemptsAreLimitedPerAccountEvenFromChangingAddresses() throws Exception {
        String body = json.writeValueAsString(Map.of("email", "target" + System.nanoTime() + "@example.com", "password", "guess"));
        for (int i = 1; i <= 10; i++) {
            String ip = "10.77.0." + i;
            mvc.perform(post("/api/auth/login").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(csrf()).with(r -> { r.setRemoteAddr("10.77.1.1"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
    }

    @Test
    void catalogueFiltersAndHidesDisguisedTechniqueTags() throws Exception {
        Cookie s = register("hub");
        JsonNode all = body(mvc.perform(get("/api/problems?size=100").cookie(s)).andReturn());
        assertThat(all.get("total").asInt()).isEqualTo(12);
        JsonNode hard = body(mvc.perform(get("/api/problems?difficulty=HARD").cookie(s)).andReturn());
        assertThat(hard.get("items")).extracting(n -> n.get("title").asText()).containsExactly("Bakery Ovens");
        assertThat(hard.get("items").get(0).get("tagsHidden").asBoolean()).isTrue();
        JsonNode bs = body(mvc.perform(get("/api/problems?tag=binary-search").cookie(s)).andReturn());
        assertThat(bs.get("items")).extracting(n -> n.get("title").asText()).containsExactlyInAnyOrder("First At Least", "Courier Capacity");
        JsonNode detail = body(mvc.perform(get("/api/problems/" + problemId("Bakery Ovens")).cookie(s)).andReturn());
        assertThat(detail.get("concepts")).isEmpty();
        assertThat(detail.get("samples")).hasSize(2);
        assertThat(detail.has("referencePy")).isFalse();
        JsonNode judgeable = body(mvc.perform(get("/api/problems?judgeable=true&q=range").cookie(s)).andReturn());
        assertThat(judgeable.get("total").asInt()).isEqualTo(1);
    }

    @Test
    void manualLinksDeduplicateAndStayPrivate() throws Exception {
        Cookie alice = register("alice");
        JsonNode first = body(mvc.perform(jsonPost("/api/problems/link", alice, Map.of("url", "https://codeforces.com/contest/4/problem/a"))).andReturn());
        assertThat(first.get("created").asBoolean()).isTrue();
        assertThat(first.get("canonicalUrl").asText()).isEqualTo("https://codeforces.com/problemset/problem/4/A");
        JsonNode again = body(mvc.perform(jsonPost("/api/problems/link", alice, Map.of("url", "https://codeforces.com/problemset/problem/4/A?locale=en"))).andReturn());
        assertThat(again.get("created").asBoolean()).isFalse();
        assertThat(again.get("id").asLong()).isEqualTo(first.get("id").asLong());
        JsonNode other = body(mvc.perform(jsonPost("/api/problems/link", alice, Map.of("url", "https://example.org/puzzles/7/"))).andReturn());
        assertThat(other.get("source").asText()).isEqualTo("MANUAL");

        Cookie bob = register("bob");
        mvc.perform(get("/api/problems/" + first.get("id").asLong()).cookie(bob)).andExpect(status().isNotFound());
        mvc.perform(put("/api/problems/" + first.get("id").asLong() + "/state").cookie(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SOLVED\"}")).andExpect(status().isNotFound());
        mvc.perform(jsonPost("/api/submissions", bob, Map.of("problemId", first.get("id").asLong(), "language", "python", "code", "print(1)")))
                .andExpect(status().isNotFound());
        mvc.perform(jsonPost("/api/problems/link", bob, Map.of("url", "javascript:alert(1)"))).andExpect(status().isBadRequest());
        // a link-only problem has no tests, so it cannot be submitted for judging
        mvc.perform(jsonPost("/api/submissions", alice, Map.of("problemId", first.get("id").asLong(), "language", "python", "code", "print(1)")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preferencesAreValidated() throws Exception {
        Cookie s = register("prefs");
        var good = Map.of("preferredLanguage", "cpp", "dailyMinutes", 90, "targetDifficulty", "HARD", "goalText", "Placement OA",
                "goalConcepts", List.of("dp", "binary-search"), "showStreak", true);
        JsonNode saved = body(mvc.perform(put("/api/me/preferences").cookie(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(good))).andExpect(status().isOk()).andReturn());
        assertThat(saved.get("goalConcepts")).hasSize(2);
        mvc.perform(put("/api/me/preferences").cookie(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("preferredLanguage", "rust", "dailyMinutes", 5, "targetDifficulty", "EASY", "showStreak", false))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/preferences").cookie(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("preferredLanguage", "java", "dailyMinutes", 30, "targetDifficulty", "EASY",
                        "goalConcepts", List.of("not-a-concept"), "showStreak", false)))).andExpect(status().isBadRequest());
    }

    @Test
    void stalledJobsAreRequeuedThenFailedAndTheSubmissionMarked() throws Exception {
        Cookie s = register("stall");
        long uid = userId(s);
        long sid = jdbc.sql("insert into submissions (user_id, problem_id, language, code, status) values (?, ?, 'python', 'print(1)', 'RUNNING') returning id")
                .params(uid, problemId("Range Totals")).query(Long.class).single();
        long job = queue.enqueue("JUDGE", uid, sid, Map.of());
        jdbc.sql("update execution_jobs set status = 'RUNNING', attempts = 1, locked_at = now() - interval '1 hour' where id = ?").param(job).update();
        var recovered = queue.recoverStale(60, 2);
        assertThat(recovered).anyMatch(r -> r.id() == job && r.status().equals("QUEUED"));
        jdbc.sql("update execution_jobs set status = 'RUNNING', attempts = 2, locked_at = now() - interval '1 hour' where id = ?").param(job).update();
        assertThat(queue.recoverStale(60, 2)).anyMatch(r -> r.id() == job && r.status().equals("FAILED"));
        judge.markInternalError(sid, "The worker stopped while judging; please resubmit");  // what the worker's housekeeping does
        assertThat(jdbc.sql("select status from submissions where id = ?").param(sid).query(String.class).single()).isEqualTo("INTERNAL_ERROR");
    }
}
