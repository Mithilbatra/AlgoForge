package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.learning.LearningService;
import com.algoforge.learning.RecommendationService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Learning evidence and the daily session against the real schema. */
class LearningIntegrationTest extends IntegrationTest {
    @Autowired LearningService learning;
    @Autowired RecommendationService recommendations;

    long accepted(long uid, long problem) {
        return jdbc.sql("""
                insert into submissions (user_id, problem_id, language, code, status, passed, total, judged_at)
                values (?, ?, 'python', 'print(1)', 'ACCEPTED', 8, 8, now()) returning id""").params(uid, problem).query(Long.class).single();
    }

    @Test
    void oldFailuresFadeEvenWhenNewEvidenceArrives() throws Exception {
        long uid = userId(register("fade"));
        jdbc.sql("""
                insert into concept_confidence (user_id, concept_code, dimension, successes, failures, evidence_count, last_evidence_at)
                values (?, 'dp', 'IMPLEMENTATION', 0, 10, 10, now() - interval '180 days')""").param(uid).update();
        learning.recordJudged(accepted(uid, problemId("Stepping Stones")));
        double estimate = learning.concepts(uid).stream().filter(c -> c.code().equals("dp")).findFirst().orElseThrow()
                .dimensions().stream().filter(d -> d.dimension().equals("IMPLEMENTATION")).findFirst().orElseThrow().estimate();
        // Six half-lives leave the ten failures weighing about 0.16: (1 + 1) / (2 + 1 + 0.16) ≈ 0.63, not 2 / 13 ≈ 0.15.
        assertThat(estimate).isBetween(0.6, 0.66);
    }

    @Test
    void disguisedRecommendationsDoNotNameTheTechnique() throws Exception {
        long uid = userId(register("disguise"));
        // Courier Capacity is binary search in a variant setting; its next step is the disguised Bakery Ovens.
        var next = recommendations.transferFor(uid, problemId("Courier Capacity")).orElseThrow();
        assertThat(next.problemId()).isEqualTo(problemId("Bakery Ovens"));
        assertThat(next.reason()).doesNotContainIgnoringCase("binary").doesNotContain("Courier Capacity");
        assertThat(next.conceptCode()).isNull();
    }

    @Test
    void concurrentRequestsBuildTodaysSessionOnce() throws Exception {
        long uid = userId(register("today"));
        var a = CompletableFuture.supplyAsync(() -> recommendations.today(uid));
        var b = CompletableFuture.supplyAsync(() -> recommendations.today(uid));
        assertThat(a.join()).isEqualTo(b.join());
        var counts = jdbc.sql("select count(*) as n, count(distinct problem_id) as d from recommendations where user_id = ?")
                .param(uid).query().singleRow();
        assertThat(((Number) counts.get("n")).intValue()).isBetween(1, 5).isEqualTo(((Number) counts.get("d")).intValue());
    }
}
