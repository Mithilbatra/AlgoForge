package com.algoforge.oa;

import com.algoforge.learning.RecommendationService;
import com.algoforge.oa.OaScoring.Attempt;
import com.algoforge.oa.OaScoring.ProblemScore;
import com.algoforge.oa.OaScoring.ProblemSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Session state machine: ACTIVE -> FINALIZING (submitted early, or the server clock passed ends_at) -> COMPLETED
 * once every in-time submission is judged. If judging stays stuck for 15 minutes the session completes anyway and
 * the unjudged attempts score 0, so finalisation is always reached and always deterministic.
 */
@Service
public class OaFinalizer {
    static final Duration STUCK_JUDGING = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final RecommendationService recommendations;

    public OaFinalizer(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json, RecommendationService recommendations) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.recommendations = recommendations;
    }

    record Session(long id, long userId, long definitionId, String status, boolean expired, OffsetDateTime startedAt,
                   OffsetDateTime endsAt, OffsetDateTime endedAt, String endReason, boolean stuck) {}

    public void finalizeIfReady(long sessionId) {
        tx.executeWithoutResult(t -> {
            Session s = lock(sessionId);
            if (s == null || s.status().equals("COMPLETED")) return;
            if (s.status().equals("ACTIVE")) {
                if (!s.expired()) return;
                jdbc.sql("update oa_sessions set status = 'FINALIZING', ended_at = ends_at, end_reason = 'EXPIRED' where id = ?")
                        .param(sessionId).update();
                s = lock(sessionId);
            }
            long pending = jdbc.sql("select count(*) from submissions where oa_session_id = ? and status in ('QUEUED', 'COMPILING', 'RUNNING')")
                    .param(sessionId).query(Long.class).single();
            if (pending > 0 && !s.stuck()) return;
            Map<String, Object> report = report(s);
            jdbc.sql("""
                    update oa_sessions set status = 'COMPLETED', finalized_at = now(), total_score = ?, max_score = ?,
                           report = cast(? as jsonb) where id = ?""")
                    .params(report.get("totalScore"), report.get("maxScore"), write(report), sessionId).update();
        });
    }

    /** Explicit submission by the candidate; later attempts are rejected by the submit endpoint. */
    public void endEarly(long sessionId) {
        jdbc.sql("""
                update oa_sessions set status = 'FINALIZING', ended_at = least(now(), ends_at),
                       end_reason = case when now() < ends_at then 'SUBMITTED' else 'EXPIRED' end
                 where id = ? and status = 'ACTIVE'""").param(sessionId).update();
        finalizeIfReady(sessionId);
    }

    private Session lock(long id) {
        return jdbc.sql("""
                select id, user_id, definition_id, status, now() >= ends_at as expired, started_at, ends_at, ended_at, end_reason,
                       coalesce(ended_at < now() - make_interval(secs => ?), false) as stuck
                  from oa_sessions where id = ? for update""")
                .params(STUCK_JUDGING.toSeconds(), id).query(Session.class).optional().orElse(null);
    }

    record Def(boolean partialCredit, int wrongPenaltyPct) {}

    record Prob(long problemId, String title, int points, int ordinal) {}

    record Sub(long id, long problemId, String status, Integer passed, Integer total, OffsetDateTime createdAt, String message,
               String firstFailKind) {}

    Map<String, Object> report(Session s) {
        Def def = jdbc.sql("select partial_credit, wrong_penalty_pct from oa_definitions where id = ?").param(s.definitionId()).query(Def.class).single();
        List<Prob> probs = jdbc.sql("""
                select op.problem_id, p.title, op.points, op.ordinal from oa_problems op join problems p on p.id = op.problem_id
                 where op.definition_id = ? order by op.ordinal""").param(s.definitionId()).query(Prob.class).list();
        List<Sub> subs = jdbc.sql("""
                select s.id, s.problem_id, s.status, s.passed, s.total, s.created_at, s.message,
                       (select r.kind from submission_results r where r.submission_id = s.id and r.verdict <> 'ACCEPTED' order by r.ordinal limit 1) as first_fail_kind
                  from submissions s where s.oa_session_id = ? order by s.created_at, s.id""").param(s.id()).query(Sub.class).list();
        List<ProblemScore> scores = OaScoring.score(
                probs.stream().map(p -> new ProblemSpec(p.problemId(), p.points())).toList(),
                subs.stream().map(x -> new Attempt(x.id(), x.problemId(), x.status(), nz(x.passed()), nz(x.total()), x.createdAt().toInstant())).toList(),
                def.partialCredit(), def.wrongPenaltyPct());
        OffsetDateTime end = s.endedAt() == null ? s.endsAt() : s.endedAt();
        Map<Long, Long> timeSpent = timePerProblem(s, end);
        double used = Math.max(1, Duration.between(s.startedAt(), end).toSeconds());

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> revise = new LinkedHashSet<>();
        List<String> insights = new ArrayList<>();
        double total = 0;
        int max = 0;
        for (Prob p : probs) {
            ProblemScore sc = scores.stream().filter(x -> x.problemId() == p.problemId()).findFirst().orElseThrow();
            List<Sub> mine = subs.stream().filter(x -> x.problemId() == p.problemId()).toList();
            Map<String, Long> verdicts = mine.stream().collect(Collectors.groupingBy(Sub::status, LinkedHashMap::new, Collectors.counting()));
            long sampleFails = mine.stream().filter(x -> "SAMPLE".equals(x.firstFailKind())).count();
            long hiddenFails = mine.stream().filter(x -> "OFFICIAL".equals(x.firstFailKind())).count();
            long spent = timeSpent.getOrDefault(p.problemId(), 0L);
            double ratio = sc.score() / p.points();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("problemId", p.problemId());
            row.put("title", p.title());
            row.put("points", p.points());
            row.put("score", sc.score());
            row.put("solved", "ACCEPTED".equals(sc.bestStatus()));
            row.put("partial", ratio > 0 && ratio < 1);
            row.put("attempts", sc.attempts());
            row.put("rejected", sc.rejected());
            row.put("verdicts", verdicts);
            row.put("failedOnSamples", sampleFails);
            row.put("failedOnHiddenTests", hiddenFails);
            row.put("timeSpentSeconds", spent);
            row.put("bestSubmissionId", sc.bestSubmissionId());
            if (sc.bestSubmissionId() != null) row.put("evaluation", evaluationSummary(sc.bestSubmissionId()));
            rows.add(row);
            total += sc.score();
            max += p.points();
            if (ratio < 1) revise.addAll(jdbc.sql("select concept_code from problem_concepts where problem_id = ?").param(p.problemId()).query(String.class).list());
            if (spent >= 300 && spent / used >= 0.4 && ratio < 0.3) {
                String other = probs.stream().filter(o -> o != p && subs.stream().noneMatch(x -> x.problemId() == o.problemId()))
                        .map(o -> "“" + o.title() + "” (" + o.points() + " pts) was never attempted").findFirst().orElse(null);
                insights.add(String.format("You spent %d min (%d%% of the time used) on “%s” for %.0f of %d points%s.", spent / 60,
                        Math.round(100 * spent / used), p.title(), sc.score(), p.points(), other == null ? "" : ", while " + other));
            }
            if (sampleFails > 0) insights.add("“" + p.title() + "”: " + sampleFails + " submission(s) failed a visible sample. Run the samples before submitting.");
            if (verdicts.getOrDefault("TIME_LIMIT_EXCEEDED", 0L) > 0 && ratio < 1) {
                insights.add("“" + p.title() + "” hit the time limit; check the complexity against the constraints before optimising constants.");
            }
        }
        if (subs.isEmpty()) insights.add("No submissions were made before the assessment ended.");

        List<Map<String, Object>> timeline = subs.stream().map(x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("submissionId", x.id());
            m.put("problemId", x.problemId());
            m.put("title", probs.stream().filter(p -> p.problemId() == x.problemId()).findFirst().map(Prob::title).orElse("?"));
            m.put("atSeconds", Duration.between(s.startedAt(), x.createdAt()).toSeconds());
            m.put("status", x.status());
            m.put("passed", x.passed());
            m.put("total", x.total());
            return m;
        }).toList();

        Set<Long> inSession = probs.stream().map(Prob::problemId).collect(Collectors.toSet());
        List<Map<String, Object>> followUps = recommendations.followUps(s.userId(), revise, inSession, 4).stream().map(c -> Map.<String, Object>of(
                "problemId", c.problemId(), "concept", c.conceptCode(), "reason", c.reason(),
                "title", jdbc.sql("select title from problems where id = ?").param(c.problemId()).query(String.class).single())).toList();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalScore", Math.round(total * 100) / 100.0);
        r.put("maxScore", max);
        r.put("percentage", max == 0 ? 0 : Math.round(1000.0 * total / max) / 10.0);
        r.put("endReason", s.endReason());
        r.put("secondsUsed", (long) used);
        r.put("solved", rows.stream().filter(x -> Boolean.TRUE.equals(x.get("solved"))).count());
        r.put("partiallySolved", rows.stream().filter(x -> Boolean.TRUE.equals(x.get("partial"))).count());
        r.put("problems", rows);
        r.put("timeline", timeline);
        r.put("insights", insights);
        r.put("conceptsToRevise", revise);
        r.put("followUps", followUps);
        r.put("scoringRules", Map.of("partialCredit", def.partialCredit(), "wrongPenaltyPct", def.wrongPenaltyPct()));
        return r;
    }

    private Map<String, Object> evaluationSummary(long submissionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        jdbc.sql("select id, overall_score, findings::text from evaluation_reports where submission_id = ?").param(submissionId)
                .query((rs, i) -> {
                    m.put("evaluationId", rs.getLong(1));
                    m.put("overallScore", rs.getDouble(2));
                    try {
                        List<String> perf = new ArrayList<>();
                        json.readTree(rs.getString(3)).forEach(f -> {
                            if (List.of("performance", "language").contains(f.path("category").asText())) perf.add(f.path("message").asText());
                        });
                        m.put("optimisationFindings", perf);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return 0;
                }).list();
        jdbc.sql("select time_complexity from complexity_assessments where submission_id = ? and source = 'static'").param(submissionId)
                .query(String.class).optional().ifPresent(t -> m.put("staticTimeComplexity", t));
        return m;
    }

    /** Focus events split the session into intervals; each interval counts toward the problem that was open. */
    private Map<Long, Long> timePerProblem(Session s, OffsetDateTime end) {
        record Ev(long problemId, OffsetDateTime at) {}
        List<Ev> evs = jdbc.sql("select problem_id, at from oa_events where session_id = ? and at <= ? order by at, id")
                .params(s.id(), end).query(Ev.class).list();
        Map<Long, Long> out = new HashMap<>();
        for (int i = 0; i < evs.size(); i++) {
            OffsetDateTime until = i + 1 < evs.size() ? evs.get(i + 1).at() : end;
            out.merge(evs.get(i).problemId(), Math.max(0, Duration.between(evs.get(i).at(), until).toSeconds()), Long::sum);
        }
        return out;
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int nz(Integer i) { return i == null ? 0 : i; }
}
