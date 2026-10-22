package com.algoforge.learning;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Evidence-based concept estimates. Every judged submission becomes weighted evidence on the concepts of its problem,
 * split by dimension (implementation, transfer to less familiar settings, recognition without hints, time pressure,
 * retention, complexity reasoning). Estimates are Beta-posterior means that decay toward 0.5 as evidence ages, and a
 * concept is never reported as strong from a single accepted submission.
 */
@Service
public class LearningService {
    public static final List<String> DIMENSIONS = List.of("IMPLEMENTATION", "TRANSFER", "RECOGNITION", "TIME_PRESSURE",
            "RETENTION", "COMPLEXITY", "EXPLANATION");
    static final double HALF_LIFE_DAYS = 30;
    static final double MIN_EVIDENCE = 3;

    private final JdbcClient jdbc;

    public LearningService(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Judged(long userId, long problemId, String status, Long oaSessionId, String oaMode, OffsetDateTime createdAt,
                  OffsetDateTime revealedAt) {}

    record ConceptTier(String conceptCode, int tier) {}

    public void recordJudged(long submissionId) {
        boolean done = jdbc.sql("select count(*) from learning_events where submission_id = ? and dimension <> 'COMPLEXITY'")
                .param(submissionId).query(Long.class).single() > 0;
        if (done) return;
        Judged j = jdbc.sql("""
                select s.user_id, s.problem_id, s.status, s.oa_session_id, d.mode as oa_mode, s.created_at, ups.revealed_at
                  from submissions s left join oa_sessions o on o.id = s.oa_session_id
                  left join oa_definitions d on d.id = o.definition_id
                  left join user_problem_state ups on ups.user_id = s.user_id and ups.problem_id = s.problem_id
                 where s.id = ?""").param(submissionId).query(Judged.class).single();
        if (List.of("COMPILATION_ERROR", "INTERNAL_ERROR").contains(j.status())) return;  // says nothing about the concept
        boolean ok = "ACCEPTED".equals(j.status());
        OffsetDateTime priorAc = jdbc.sql("""
                select max(created_at) from submissions where user_id = ? and problem_id = ? and status = 'ACCEPTED' and id < ?""")
                .params(j.userId(), j.problemId(), submissionId).query(OffsetDateTime.class).optional().orElse(null);
        if (ok && priorAc != null && priorAc.isAfter(j.createdAt().minusDays(3))) return;  // a re-submit, not new evidence
        if (!ok && priorAc == null) {
            long earlierFails = jdbc.sql("""
                    select count(*) from submissions where user_id = ? and problem_id = ? and id < ?
                       and status not in ('ACCEPTED', 'COMPILATION_ERROR', 'INTERNAL_ERROR', 'QUEUED', 'COMPILING', 'RUNNING')""")
                    .params(j.userId(), j.problemId(), submissionId).query(Long.class).single();
            if (earlierFails >= 3) return;  // cap repeated failures on one problem
        }
        double weight = (ok ? 1.0 : 0.5) * (j.revealedAt() != null && j.revealedAt().isBefore(j.createdAt()) ? 0.25 : 1.0);
        boolean assessment = "ASSESSMENT".equals(j.oaMode());
        String context = assessment ? "ASSESSMENT" : "PRACTICE";
        boolean retention = priorAc != null && priorAc.isBefore(j.createdAt().minusDays(7));
        for (ConceptTier c : conceptsOf(j.problemId())) {
            event(j.userId(), c.conceptCode(), "IMPLEMENTATION", ok, weight, context, j.problemId(), submissionId);
            if (c.tier() >= 2) event(j.userId(), c.conceptCode(), "TRANSFER", ok, weight, context, j.problemId(), submissionId);
            if (c.tier() == 3 || assessment) event(j.userId(), c.conceptCode(), "RECOGNITION", ok, weight, context, j.problemId(), submissionId);
            if (assessment) event(j.userId(), c.conceptCode(), "TIME_PRESSURE", ok, weight, context, j.problemId(), submissionId);
            if (retention) event(j.userId(), c.conceptCode(), "RETENTION", ok, weight, context, j.problemId(), submissionId);
        }
        if (ok) {
            jdbc.sql("""
                    update recommendations set status = 'COMPLETED', acted_at = now()
                     where user_id = ? and problem_id = ? and status in ('PENDING', 'ACCEPTED')""")
                    .params(j.userId(), j.problemId()).update();
        }
    }

    /** Whether the complexity the learner stated matched the measured or high-confidence estimate. Once per submission. */
    public void recordComplexityClaim(long submissionId, boolean correct) {
        boolean done = jdbc.sql("select count(*) from learning_events where submission_id = ? and dimension = 'COMPLEXITY'")
                .param(submissionId).query(Long.class).single() > 0;
        if (done) return;
        record S(long userId, long problemId, Long oaSessionId) {}
        S s = jdbc.sql("select user_id, problem_id, oa_session_id from submissions where id = ?").param(submissionId).query(S.class).single();
        for (ConceptTier c : conceptsOf(s.problemId())) {
            event(s.userId(), c.conceptCode(), "COMPLEXITY", correct, 1.0, s.oaSessionId() == null ? "PRACTICE" : "ASSESSMENT",
                    s.problemId(), submissionId);
        }
    }

    List<ConceptTier> conceptsOf(long problemId) {
        return jdbc.sql("select concept_code, tier from problem_concepts where problem_id = ?").param(problemId).query(ConceptTier.class).list();
    }

    private void event(long userId, String concept, String dimension, boolean success, double weight, String context,
                       long problemId, long submissionId) {
        jdbc.sql("""
                insert into learning_events (user_id, concept_code, dimension, success, weight, context, problem_id, submission_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(userId, concept, dimension, success, weight, context, problemId, submissionId).update();
        jdbc.sql("""
                insert into concept_confidence (user_id, concept_code, dimension, successes, failures, evidence_count,
                    last_evidence_at, last_success_at)
                values (:u, :c, :d, case when :ok then :w else 0 end, case when :ok then 0 else :w end, 1, now(), case when :ok then now() end)
                on conflict (user_id, concept_code, dimension) do update set
                    successes = concept_confidence.successes + excluded.successes,
                    failures = concept_confidence.failures + excluded.failures,
                    evidence_count = concept_confidence.evidence_count + 1,
                    last_evidence_at = now(),
                    last_success_at = coalesce(excluded.last_success_at, concept_confidence.last_success_at)""")
                .param("u", userId).param("c", concept).param("d", dimension).param("ok", success).param("w", weight).update();
    }

    // ---- estimates --------------------------------------------------------------------------------------------------

    public record DimensionEstimate(String dimension, Double estimate, double evidence, OffsetDateTime lastEvidenceAt) {}

    public record ConceptMastery(String code, String name, String description, List<String> prerequisites, String status,
                                 Double mastery, double evidence, List<DimensionEstimate> dimensions, boolean dueForRevision,
                                 OffsetDateTime lastSuccessAt, int problemsSolved, int problemsAvailable) {}

    record Row(String conceptCode, String dimension, double successes, double failures, OffsetDateTime lastEvidenceAt,
               OffsetDateTime lastSuccessAt) {}

    /** Beta(1,1) prior; evidence decays with a 30-day half-life, so stale estimates drift back toward 0.5. */
    static DimensionEstimate estimate(String dimension, double successes, double failures, OffsetDateTime last, OffsetDateTime now) {
        if (last == null || successes + failures == 0) return new DimensionEstimate(dimension, null, 0, null);
        double days = Math.max(0, Duration.between(last, now).toHours() / 24.0);
        double decay = Math.pow(0.5, days / HALF_LIFE_DAYS);
        double s = successes * decay, f = failures * decay;
        return new DimensionEstimate(dimension, (1 + s) / (2 + s + f), Math.round((s + f) * 100) / 100.0, last);
    }

    static String status(List<DimensionEstimate> dims) {
        double evidence = dims.stream().mapToDouble(DimensionEstimate::evidence).sum();
        if (evidence < MIN_EVIDENCE) return "INSUFFICIENT_EVIDENCE";
        double m = mastery(dims);
        if (m < 0.45) return "WEAK";
        boolean transferShown = dims.stream().anyMatch(d -> d.dimension().equals("TRANSFER") && d.estimate() != null && d.estimate() >= 0.6);
        return m >= 0.7 && transferShown ? "STRONG" : "DEVELOPING";
    }

    static double mastery(List<DimensionEstimate> dims) {
        double w = 0, sum = 0;
        for (DimensionEstimate d : dims) {
            if (d.estimate() == null) continue;
            w += d.evidence();
            sum += d.evidence() * d.estimate();
        }
        return w == 0 ? 0.5 : sum / w;
    }

    public List<ConceptMastery> concepts(long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Map<String, Row>> rows = new HashMap<>();
        jdbc.sql("select concept_code, dimension, successes, failures, last_evidence_at, last_success_at from concept_confidence where user_id = ?")
                .param(userId).query(Row.class).list()
                .forEach(r -> rows.computeIfAbsent(r.conceptCode(), k -> new HashMap<>()).put(r.dimension(), r));
        Map<String, List<String>> prereqs = new HashMap<>();
        jdbc.sql("select concept_code, requires from concept_prerequisites").query((rs, i) -> List.of(rs.getString(1), rs.getString(2))).list()
                .forEach(p -> prereqs.computeIfAbsent(p.get(0), k -> new ArrayList<>()).add(p.get(1)));
        record C(String code, String name, String description, int solved, int available) {}
        List<C> cs = jdbc.sql("""
                select c.code, c.name, c.description,
                       (select count(*) from problem_concepts pc join user_problem_state u on u.problem_id = pc.problem_id
                         where pc.concept_code = c.code and u.user_id = :u and u.status = 'SOLVED') as solved,
                       (select count(*) from problem_concepts pc join problems p on p.id = pc.problem_id
                         where pc.concept_code = c.code and (p.owner_id is null or p.owner_id = :u)) as available
                  from concepts c order by c.sort_order""").param("u", userId).query(C.class).list();
        List<ConceptMastery> out = new ArrayList<>();
        for (C c : cs) {
            Map<String, Row> byDim = rows.getOrDefault(c.code(), Map.of());
            List<DimensionEstimate> dims = new ArrayList<>();
            OffsetDateTime lastSuccess = null;
            for (String d : DIMENSIONS) {
                Row r = byDim.get(d);
                dims.add(r == null ? new DimensionEstimate(d, null, 0, null) : estimate(d, r.successes(), r.failures(), r.lastEvidenceAt(), now));
                if (r != null && r.lastSuccessAt() != null && (lastSuccess == null || r.lastSuccessAt().isAfter(lastSuccess))) lastSuccess = r.lastSuccessAt();
            }
            String status = status(dims);
            double evidence = Math.round(dims.stream().mapToDouble(DimensionEstimate::evidence).sum() * 100) / 100.0;
            boolean due = lastSuccess != null && lastSuccess.isBefore(now.minusDays(14)) && !status.equals("WEAK");
            out.add(new ConceptMastery(c.code(), c.name(), c.description(), prereqs.getOrDefault(c.code(), List.of()), status,
                    evidence == 0 ? null : Math.round(mastery(dims) * 1000) / 1000.0, evidence, dims, due, lastSuccess,
                    c.solved(), c.available()));
        }
        return out;
    }
}
