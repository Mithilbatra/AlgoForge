package com.algoforge.learning;

import com.algoforge.common.ApiException;
import com.algoforge.learning.LearningService.ConceptMastery;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily training sessions and the Transfer Test Engine. Candidates are ordered: transfer steps after recent
 * solves (same concept, less familiar setting), goal concepts, weak concepts, revision of old solves, then new
 * concepts whose prerequisites are not weak. Every pick carries the reason it was chosen.
 */
@Service
public class RecommendationService {
    static final int DEFAULT_MINUTES = 20;
    static final int MAX_ITEMS = 5;

    private final JdbcClient jdbc;
    private final LearningService learning;

    public RecommendationService(JdbcClient jdbc, LearningService learning) {
        this.jdbc = jdbc;
        this.learning = learning;
    }

    public record Rec(long id, long problemId, String title, String difficulty, Integer estMinutes, String conceptCode,
                      String kind, String reason, String status) {}

    /** A candidate before it is stored. */
    public record Candidate(long problemId, String conceptCode, String kind, String reason) {}

    record Problem(long id, String title, String difficulty, Integer estMinutes, String status, OffsetDateTime solvedAt) {}

    record ProblemConcept(long problemId, String conceptCode, int tier) {}

    static String tierPhrase(int tier) {
        return switch (tier) {
            case 1 -> "in its standard form";
            case 2 -> "in a variant setting";
            default -> "in a problem whose statement does not name the technique";
        };
    }

    @Transactional
    public List<Rec> today(long userId) {
        List<Rec> existing = stored(userId);
        if (!existing.isEmpty()) return existing;
        record Prefs(int dailyMinutes, String targetDifficulty, String goalText, String[] goalConcepts) {}
        Prefs prefs = jdbc.sql("select daily_minutes, target_difficulty, goal_text, goal_concepts from user_preferences where user_id = ?")
                .param(userId).query((rs, i) -> new Prefs(rs.getInt(1), rs.getString(2), rs.getString(3), (String[]) rs.getArray(4).getArray()))
                .single();
        int minutes = 0;
        Map<Long, Problem> problems = problems(userId);
        for (Candidate c : candidates(userId, Set.of(), prefs.targetDifficulty(), List.of(prefs.goalConcepts()), prefs.goalText())) {
            if (minutes >= prefs.dailyMinutes() || stored(userId).size() >= MAX_ITEMS) break;
            insert(userId, c);
            Problem p = problems.get(c.problemId());
            minutes += p.estMinutes() == null ? DEFAULT_MINUTES : p.estMinutes();
        }
        return stored(userId);
    }

    @Transactional
    public Rec act(long userId, long recId, String action) {
        Rec r = stored(userId).stream().filter(x -> x.id() == recId).findFirst().orElseThrow(() -> ApiException.notFound("Recommendation"));
        switch (action) {
            case "accept" -> setStatus(recId, "ACCEPTED");
            case "skip" -> setStatus(recId, "SKIPPED");
            case "replace" -> {
                setStatus(recId, "REPLACED");
                Set<Long> exclude = stored(userId).stream().map(Rec::problemId).collect(Collectors.toSet());
                exclude.addAll(jdbc.sql("select problem_id from recommendations where user_id = ? and for_date = ?")
                        .params(userId, LocalDate.now()).query(Long.class).list());
                List<Candidate> alts = candidates(userId, exclude, null, List.of(), null);
                Optional<Candidate> alt = alts.stream().filter(c -> c.conceptCode() != null && c.conceptCode().equals(r.conceptCode())).findFirst()
                        .or(() -> alts.stream().findFirst());
                if (alt.isEmpty()) throw ApiException.conflict("No other suitable problem is available right now");
                return byId(insert(userId, alt.get()));
            }
            default -> throw ApiException.badRequest("Unknown action " + action);
        }
        return byId(recId);
    }

    /** The next transfer step after solving a problem, if there is an unsolved one. */
    public Optional<Candidate> transferFor(long userId, long problemId) {
        Map<Long, Problem> problems = problems(userId);
        List<ProblemConcept> pcs = problemConcepts(userId);
        for (ProblemConcept solved : pcs.stream().filter(pc -> pc.problemId() == problemId).toList()) {
            Optional<Candidate> next = nextTier(solved, pcs, problems, Set.of(problemId));
            if (next.isPresent()) return next;
        }
        return Optional.empty();
    }

    /** Follow-up practice for a set of concepts (used by the post-assessment report). */
    public List<Candidate> followUps(long userId, Collection<String> concepts, Set<Long> exclude, int limit) {
        Map<Long, Problem> problems = problems(userId);
        List<Candidate> out = new ArrayList<>();
        Set<Long> used = new HashSet<>(exclude);
        for (String concept : concepts) {
            problemConcepts(userId).stream()
                    .filter(pc -> pc.conceptCode().equals(concept) && !used.contains(pc.problemId())
                            && !"SOLVED".equals(problems.get(pc.problemId()).status()))
                    .min((a, b) -> Integer.compare(a.tier(), b.tier()))
                    .ifPresent(pc -> {
                        used.add(pc.problemId());
                        out.add(new Candidate(pc.problemId(), concept, "WEAKNESS",
                                "Follow-up on " + concept + " " + tierPhrase(pc.tier()) + "."));
                    });
            if (out.size() >= limit) break;
        }
        return out;
    }

    List<Candidate> candidates(long userId, Set<Long> exclude, String targetDifficulty, List<String> goalConcepts, String goalText) {
        Map<Long, Problem> problems = problems(userId);
        List<ProblemConcept> pcs = problemConcepts(userId);
        Map<String, ConceptMastery> mastery = learning.concepts(userId).stream()
                .collect(Collectors.toMap(ConceptMastery::code, Function.identity()));
        Map<Long, Candidate> out = new LinkedHashMap<>();
        Set<Long> used = new HashSet<>(exclude);
        Function<ProblemConcept, Boolean> open = pc -> !used.contains(pc.problemId()) && !"SOLVED".equals(problems.get(pc.problemId()).status());

        // 1. transfer: recent solves -> same concept, next tier
        List<Long> recentSolves = jdbc.sql("""
                select problem_id from submissions where user_id = ? and status = 'ACCEPTED' and created_at > now() - interval '14 days'
                 group by problem_id order by max(created_at) desc limit 10""").param(userId).query(Long.class).list();
        for (long solved : recentSolves) {
            for (ProblemConcept pc : pcs.stream().filter(x -> x.problemId() == solved).toList()) {
                nextTier(pc, pcs, problems, used).ifPresent(c -> { used.add(c.problemId()); out.put(c.problemId(), c); });
            }
        }
        // 2. goal concepts not yet strong
        for (String g : goalConcepts) {
            ConceptMastery m = mastery.get(g);
            if (m == null || m.status().equals("STRONG")) continue;
            easiest(g, pcs, problems, open, targetDifficulty).ifPresent(pc -> {
                used.add(pc.problemId());
                out.put(pc.problemId(), new Candidate(pc.problemId(), g, "GOAL", (goalText == null ? "Goal concept" : "Goal: " + goalText)
                        + ". " + m.name() + " is " + describe(m) + "; this practises it " + tierPhrase(pc.tier()) + "."));
            });
        }
        // 3. weak concepts, weakest first
        mastery.values().stream().filter(m -> m.status().equals("WEAK"))
                .sorted((a, b) -> Double.compare(a.mastery(), b.mastery()))
                .forEach(m -> easiest(m.code(), pcs, problems, open, targetDifficulty).ifPresent(pc -> {
                    used.add(pc.problemId());
                    out.put(pc.problemId(), new Candidate(pc.problemId(), m.code(), "WEAKNESS",
                            m.name() + " is a weak spot (" + describe(m) + ")."));
                }));
        // 4. revision of problems solved two or more weeks ago
        problems.values().stream()
                .filter(p -> !used.contains(p.id()) && ("NEEDS_REVISION".equals(p.status())
                        || "SOLVED".equals(p.status()) && p.solvedAt() != null && p.solvedAt().isBefore(OffsetDateTime.now().minusDays(14))))
                .limit(2)
                .forEach(p -> {
                    used.add(p.id());
                    String concept = pcs.stream().filter(pc -> pc.problemId() == p.id()).map(ProblemConcept::conceptCode).findFirst().orElse(null);
                    String when = p.solvedAt() == null ? "Marked for revision" : "Solved " + ChronoUnit.DAYS.between(p.solvedAt(), OffsetDateTime.now()) + " days ago";
                    out.put(p.id(), new Candidate(p.id(), concept, "REVISION", when + "; solving it again from scratch checks retention."));
                });
        // 5. new concepts whose prerequisites are in place (practised to DEVELOPING or better, or not practisable here)
        mastery.values().stream().filter(m -> m.evidence() == 0 && m.prerequisites().stream().allMatch(req -> ready(mastery.get(req))))
                .forEach(m -> easiest(m.code(), pcs, problems, open, targetDifficulty).ifPresent(pc -> {
                    used.add(pc.problemId());
                    out.put(pc.problemId(), new Candidate(pc.problemId(), m.code(), "NEW", "New concept: " + m.name()
                            + (m.prerequisites().isEmpty() ? ", a foundation for later topics."
                               : ". Its prerequisites (" + String.join(", ", m.prerequisites()) + ") are in place.")));
                }));
        // 6. fill any remaining time with standard-form problems, foundations first
        for (String concept : jdbc.sql("select code from concepts order by sort_order").query(String.class).list()) {
            pcs.stream().filter(pc -> pc.conceptCode().equals(concept) && pc.tier() == 1 && open.apply(pc)).forEach(pc -> {
                used.add(pc.problemId());
                out.putIfAbsent(pc.problemId(), new Candidate(pc.problemId(), concept, "NEW", "Standard-form practice on "
                        + mastery.get(concept).name() + ", building the evidence needed before its variants."));
            });
        }
        return List.copyOf(out.values());
    }

    private Optional<Candidate> nextTier(ProblemConcept solved, List<ProblemConcept> pcs, Map<Long, Problem> problems, Set<Long> used) {
        return pcs.stream()
                .filter(pc -> pc.conceptCode().equals(solved.conceptCode()) && pc.tier() > solved.tier()
                        && !used.contains(pc.problemId()) && pc.problemId() != solved.problemId()
                        && !"SOLVED".equals(problems.get(pc.problemId()).status()))
                .min((a, b) -> Integer.compare(a.tier(), b.tier()))
                .map(pc -> new Candidate(pc.problemId(), pc.conceptCode(), "TRANSFER",
                        "Transfer test: you solved “" + problems.get(solved.problemId()).title() + "” (" + solved.conceptCode() + " "
                                + tierPhrase(solved.tier()) + "). This applies the same idea " + tierPhrase(pc.tier()) + "."));
    }

    private Optional<ProblemConcept> easiest(String concept, List<ProblemConcept> pcs, Map<Long, Problem> problems,
                                             Function<ProblemConcept, Boolean> open, String targetDifficulty) {
        return pcs.stream().filter(pc -> pc.conceptCode().equals(concept) && open.apply(pc))
                .min((a, b) -> a.tier() != b.tier() ? Integer.compare(a.tier(), b.tier())
                        : Boolean.compare(!eq(problems.get(a.problemId()).difficulty(), targetDifficulty),
                                          !eq(problems.get(b.problemId()).difficulty(), targetDifficulty)));
    }

    private static boolean eq(String a, String b) { return a != null && a.equals(b); }

    static boolean ready(ConceptMastery prereq) {
        return prereq == null || prereq.problemsAvailable() == 0
                || prereq.status().equals("DEVELOPING") || prereq.status().equals("STRONG");
    }

    private static String describe(ConceptMastery m) {
        if (m.mastery() == null) return "untested";
        return Math.round(m.mastery() * 100) + "% estimated from " + m.evidence() + " weighted evidence";
    }

    /** Only problems AlgoForge can judge, so every recommendation produces real evidence. */
    private Map<Long, Problem> problems(long userId) {
        return jdbc.sql("""
                select p.id, p.title, p.difficulty, p.est_minutes, coalesce(u.status, 'UNSEEN') as status, u.solved_at
                  from problems p left join user_problem_state u on u.problem_id = p.id and u.user_id = ?
                 where (p.owner_id is null or p.owner_id = ?)
                   and (exists (select 1 from test_cases t where t.problem_id = p.id and t.kind in ('SAMPLE', 'OFFICIAL'))
                        or jsonb_array_length(p.generated_specs) > 0)""")
                .params(userId, userId).query(Problem.class).list().stream()
                .collect(Collectors.toMap(Problem::id, Function.identity()));
    }

    private List<ProblemConcept> problemConcepts(long userId) {
        Set<Long> judgeable = problems(userId).keySet();
        return jdbc.sql("select problem_id, concept_code, tier from problem_concepts order by problem_id")
                .query(ProblemConcept.class).list().stream().filter(pc -> judgeable.contains(pc.problemId())).toList();
    }

    private long insert(long userId, Candidate c) {
        return jdbc.sql("insert into recommendations (user_id, problem_id, concept_code, kind, reason, for_date) values (?, ?, ?, ?, ?, ?) returning id")
                .params(userId, c.problemId(), c.conceptCode(), c.kind(), c.reason(), LocalDate.now()).query(Long.class).single();
    }

    private void setStatus(long id, String status) {
        jdbc.sql("update recommendations set status = ?, acted_at = now() where id = ?").params(status, id).update();
    }

    private List<Rec> stored(long userId) {
        return jdbc.sql("""
                select r.id, r.problem_id, p.title, p.difficulty, p.est_minutes, r.concept_code, r.kind, r.reason, r.status
                  from recommendations r join problems p on p.id = r.problem_id
                 where r.user_id = ? and r.for_date = ? and r.status <> 'REPLACED' order by r.id""")
                .params(userId, LocalDate.now()).query(Rec.class).list();
    }

    private Rec byId(long id) {
        return jdbc.sql("""
                select r.id, r.problem_id, p.title, p.difficulty, p.est_minutes, r.concept_code, r.kind, r.reason, r.status
                  from recommendations r join problems p on p.id = r.problem_id where r.id = ?""").param(id).query(Rec.class).single();
    }
}
