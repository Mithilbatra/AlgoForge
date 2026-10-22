package com.algoforge.oa;

import com.algoforge.auth.CurrentUser;
import com.algoforge.common.ApiException;
import com.algoforge.oa.OaScoring.Attempt;
import com.algoforge.oa.OaScoring.ProblemSpec;
import com.algoforge.problems.ProblemService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class OaController {
    private final JdbcClient jdbc;
    private final OaFinalizer finalizer;
    private final ProblemService problems;
    private final ObjectMapper json;

    public OaController(JdbcClient jdbc, OaFinalizer finalizer, ProblemService problems, ObjectMapper json) {
        this.jdbc = jdbc;
        this.finalizer = finalizer;
        this.problems = problems;
        this.json = json;
    }

    public record Definition(long id, String title, String description, boolean isTemplate, int durationMinutes, String mode,
                             boolean partialCredit, int wrongPenaltyPct, List<String> languages, boolean allowHints,
                             List<Map<String, Object>> problems, JsonNode recipe) {}

    @GetMapping("/api/oa/definitions")
    public List<Definition> definitions(Authentication auth) {
        List<Long> ids = jdbc.sql("select id from oa_definitions where is_template or owner_id = ? order by is_template desc, id desc limit 50")
                .param(CurrentUser.id(auth)).query(Long.class).list();
        return ids.stream().map(this::definition).toList();
    }

    Definition definition(long id) {
        List<Map<String, Object>> probs = jdbc.sql("""
                select op.problem_id as "problemId", p.title, p.difficulty, op.points, op.ordinal
                  from oa_problems op join problems p on p.id = op.problem_id where op.definition_id = ? order by op.ordinal""")
                .param(id).query().listOfRows();
        return jdbc.sql("""
                select id, title, description, is_template, duration_minutes, mode, partial_credit, wrong_penalty_pct, languages,
                       allow_hints, recipe::text from oa_definitions where id = ?""").param(id)
                .query((rs, i) -> new Definition(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getInt(5),
                        rs.getString(6), rs.getBoolean(7), rs.getInt(8), List.of((String[]) rs.getArray(9).getArray()), rs.getBoolean(10),
                        probs, readJson(rs.getString(11))))
                .single();
    }

    public record ProblemPick(@NotNull Long problemId, @Min(1) @Max(1000) int points) {}

    public record RandomPick(@Min(1) @Max(6) int count, List<@Size(max = 40) String> concepts,
                             @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty, @Min(1) @Max(1000) int pointsEach) {}

    public record CreateRequest(@NotBlank @Size(max = 120) String title, @Min(5) @Max(300) int durationMinutes,
                                @Pattern(regexp = "ASSESSMENT|PRACTICE") String mode, boolean partialCredit,
                                @Min(0) @Max(50) int wrongPenaltyPct,
                                @NotEmpty List<@Pattern(regexp = "java|cpp|python") String> languages, boolean allowHints,
                                @Size(max = 8) List<@Valid ProblemPick> problems, @Valid RandomPick random) {}

    /** Custom assessment from chosen problems and/or random problems drawn from topics and a difficulty. */
    @PostMapping("/api/oa/definitions")
    @Transactional
    public Definition create(@Valid @RequestBody CreateRequest body, Authentication auth) {
        long uid = CurrentUser.id(auth);
        List<ProblemPick> picks = new ArrayList<>(body.problems() == null ? List.of() : body.problems());
        Set<Long> used = new HashSet<>();
        for (ProblemPick p : picks) {
            problems.visible(uid, p.problemId());
            if (!problems.judgeable(p.problemId())) throw ApiException.badRequest("Problem " + p.problemId() + " has no tests and cannot be judged");
            if (!used.add(p.problemId())) throw ApiException.badRequest("Duplicate problem " + p.problemId());
        }
        if (body.random() != null) {
            RandomPick r = body.random();
            List<Long> drawn = draw(uid, r.difficulty(), r.concepts(), r.count(), used);
            if (drawn.size() < r.count()) throw ApiException.badRequest("Only " + drawn.size() + " matching judgeable problems are available");
            drawn.forEach(id -> { used.add(id); picks.add(new ProblemPick(id, r.pointsEach())); });
        }
        if (picks.isEmpty()) throw ApiException.badRequest("Add at least one problem");
        long id = jdbc.sql("""
                insert into oa_definitions (owner_id, title, duration_minutes, mode, partial_credit, wrong_penalty_pct, languages, allow_hints)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(uid, body.title().trim(), body.durationMinutes(), body.mode() == null ? "ASSESSMENT" : body.mode(),
                        body.partialCredit(), body.wrongPenaltyPct(), body.languages().toArray(String[]::new), body.allowHints())
                .query(Long.class).single();
        insertProblems(id, picks);
        return definition(id);
    }

    /** Turns a template recipe into a concrete assessment, preferring judgeable problems the user has not solved. */
    @PostMapping("/api/oa/templates/{id}/instantiate")
    @Transactional
    public Definition instantiate(@PathVariable long id, Authentication auth) {
        long uid = CurrentUser.id(auth);
        Definition t = definition(id);
        if (!t.isTemplate() || t.recipe() == null) throw ApiException.notFound("Template");
        List<ProblemPick> picks = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (JsonNode slot : t.recipe().path("slots")) {
            List<Long> drawn = draw(uid, slot.path("difficulty").asText(), null, 1, used);
            if (drawn.isEmpty()) throw ApiException.conflict("Not enough " + slot.path("difficulty").asText().toLowerCase() + " problems for this template");
            used.add(drawn.getFirst());
            picks.add(new ProblemPick(drawn.getFirst(), slot.path("points").asInt()));
        }
        long newId = jdbc.sql("""
                insert into oa_definitions (owner_id, title, description, duration_minutes, mode, partial_credit, wrong_penalty_pct, languages, allow_hints)
                select ?, title, description, duration_minutes, mode, partial_credit, wrong_penalty_pct, languages, allow_hints
                  from oa_definitions where id = ? returning id""").params(uid, id).query(Long.class).single();
        insertProblems(newId, picks);
        return definition(newId);
    }

    private List<Long> draw(long uid, String difficulty, List<String> concepts, int count, Set<Long> exclude) {
        return jdbc.sql("""
                select p.id from problems p left join user_problem_state u on u.problem_id = p.id and u.user_id = :uid
                 where (p.owner_id is null or p.owner_id = :uid)
                   and (cast(:diff as text) is null or p.difficulty = :diff)
                   and (cardinality(cast(:concepts as text[])) = 0
                        or exists (select 1 from problem_concepts pc where pc.problem_id = p.id and pc.concept_code = any(cast(:concepts as text[]))))
                   and (exists (select 1 from test_cases t where t.problem_id = p.id and t.kind in ('SAMPLE', 'OFFICIAL'))
                        or jsonb_array_length(p.generated_specs) > 0)
                   and not (p.id = any(cast(:exclude as bigint[])))
                 order by coalesce(u.status, 'UNSEEN') = 'SOLVED', random() limit :n""")
                .param("uid", uid).param("diff", difficulty)
                .param("concepts", (concepts == null ? List.<String>of() : concepts).toArray(String[]::new))
                .param("exclude", exclude.toArray(Long[]::new)).param("n", count)
                .query(Long.class).list();
    }

    private void insertProblems(long definitionId, List<ProblemPick> picks) {
        for (int i = 0; i < picks.size(); i++) {
            jdbc.sql("insert into oa_problems (definition_id, problem_id, ordinal, points) values (?, ?, ?, ?)")
                    .params(definitionId, picks.get(i).problemId(), i, picks.get(i).points()).update();
        }
    }

    @PostMapping("/api/oa/definitions/{id}/start")
    public Map<String, Object> start(@PathVariable long id, Authentication auth) {
        long uid = CurrentUser.id(auth);
        Definition d = definition(id);
        boolean mine = jdbc.sql("select count(*) from oa_definitions where id = ? and owner_id = ?").params(id, uid).query(Long.class).single() > 0;
        if (!mine) throw ApiException.notFound("Assessment");
        if (d.problems().isEmpty()) throw ApiException.badRequest("This assessment has no problems");
        try {
            long sid = jdbc.sql("""
                    insert into oa_sessions (definition_id, user_id, ends_at, max_score)
                    values (?, ?, now() + make_interval(mins => ?), ?) returning id""")
                    .params(id, uid, d.durationMinutes(), d.problems().stream().mapToInt(p -> ((Number) p.get("points")).intValue()).sum())
                    .query(Long.class).single();
            return Map.of("sessionId", sid);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("You already have an assessment in progress; finish or submit it first");
        }
    }

    record SessionRow(long id, long definitionId, String status, OffsetDateTime startedAt, OffsetDateTime endsAt, long remainingMs,
                      String endReason) {}

    private SessionRow session(long id, long uid) {
        return jdbc.sql("""
                select id, definition_id, status, started_at, ends_at,
                       greatest(0, (extract(epoch from (ends_at - now())) * 1000)::bigint) as remaining_ms, end_reason
                  from oa_sessions where id = ? and user_id = ?""").params(id, uid).query(SessionRow.class).optional()
                .orElseThrow(() -> ApiException.notFound("Assessment session"));
    }

    /** Live state. The remaining time is computed by the server; the client only counts down from it. */
    @GetMapping("/api/oa/sessions/{id}")
    public Map<String, Object> state(@PathVariable long id, Authentication auth) {
        long uid = CurrentUser.id(auth);
        SessionRow s = session(id, uid);
        if (!s.status().equals("COMPLETED")) {
            finalizer.finalizeIfReady(id);
            s = session(id, uid);
        }
        Definition d = definition(s.definitionId());
        List<Attempt> attempts = jdbc.sql("""
                select id, problem_id, status, coalesce(passed, 0) as passed, coalesce(total, 0) as total, created_at
                  from submissions where oa_session_id = ? order by id""").param(id)
                .query((rs, i) -> new Attempt(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getObject(6, OffsetDateTime.class).toInstant())).list();
        var scores = OaScoring.score(d.problems().stream().map(p -> new ProblemSpec(((Number) p.get("problemId")).longValue(),
                ((Number) p.get("points")).intValue())).toList(), attempts, d.partialCredit(), d.wrongPenaltyPct());
        List<Map<String, Object>> probs = new ArrayList<>();
        for (Map<String, Object> p : d.problems()) {
            long pid = ((Number) p.get("problemId")).longValue();
            Map<String, Object> row = new LinkedHashMap<>(p);
            if ("ASSESSMENT".equals(d.mode()) && !s.status().equals("COMPLETED")) row.remove("difficulty");
            scores.stream().filter(x -> x.problemId() == pid).findFirst().ifPresent(sc -> {
                row.put("score", sc.score());
                row.put("attempts", sc.attempts());
                row.put("bestStatus", sc.bestStatus());
            });
            attempts.stream().filter(a -> a.problemId() == pid).reduce((a, b) -> b).ifPresent(a -> row.put("lastStatus", a.status()));
            probs.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id());
        out.put("title", d.title());
        out.put("status", s.status());
        out.put("mode", d.mode());
        out.put("startedAt", s.startedAt());
        out.put("endsAt", s.endsAt());
        out.put("remainingMs", s.status().equals("ACTIVE") ? s.remainingMs() : 0);
        out.put("endReason", s.endReason());
        out.put("languages", d.languages());
        out.put("allowHints", d.allowHints());
        out.put("partialCredit", d.partialCredit());
        out.put("wrongPenaltyPct", d.wrongPenaltyPct());
        out.put("pointsEarned", Math.round(scores.stream().mapToDouble(OaScoring.ProblemScore::score).sum() * 100) / 100.0);
        out.put("maxPoints", d.problems().stream().mapToInt(p -> ((Number) p.get("points")).intValue()).sum());
        out.put("problems", probs);
        out.put("submissions", attempts.stream().map(a -> Map.of("id", a.submissionId(), "problemId", a.problemId(),
                "status", a.status(), "passed", a.passed(), "total", a.total(), "at", a.at().toString())).toList());
        return out;
    }

    public record Focus(@NotNull Long problemId) {}

    @PostMapping("/api/oa/sessions/{id}/focus")
    public void focus(@PathVariable long id, @Valid @RequestBody Focus body, Authentication auth) {
        SessionRow s = session(id, CurrentUser.id(auth));
        if (!s.status().equals("ACTIVE")) return;
        jdbc.sql("""
                insert into oa_events (session_id, problem_id)
                select ?, ? where exists (select 1 from oa_problems where definition_id = ? and problem_id = ?)
                   and exists (select 1 from oa_sessions where id = ? and now() < ends_at)""")
                .params(id, body.problemId(), s.definitionId(), body.problemId(), id).update();
    }

    @PostMapping("/api/oa/sessions/{id}/submit")
    public Map<String, Object> submit(@PathVariable long id, Authentication auth) {
        long uid = CurrentUser.id(auth);
        session(id, uid);
        finalizer.endEarly(id);
        return state(id, auth);
    }

    @GetMapping("/api/oa/sessions/{id}/report")
    public Map<String, Object> report(@PathVariable long id, Authentication auth) {
        long uid = CurrentUser.id(auth);
        SessionRow s = session(id, uid);
        if (!s.status().equals("COMPLETED")) finalizer.finalizeIfReady(id);
        String report = jdbc.sql("select report::text from oa_sessions where id = ? and status = 'COMPLETED'").param(id)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.conflict("The report is ready once the assessment ends and all submissions are judged"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", id);
        out.put("title", definition(s.definitionId()).title());
        out.put("startedAt", s.startedAt());
        out.put("report", readJson(report));
        return out;
    }

    @GetMapping("/api/oa/sessions")
    public List<Map<String, Object>> sessions(Authentication auth) {
        return jdbc.sql("""
                select o.id, d.title, o.status, o.started_at as "startedAt", o.ends_at as "endsAt", o.total_score as "totalScore",
                       o.max_score as "maxScore", o.end_reason as "endReason"
                  from oa_sessions o join oa_definitions d on d.id = o.definition_id where o.user_id = ? order by o.id desc limit 50""")
                .param(CurrentUser.id(auth)).query().listOfRows();
    }

    /** Expired sessions are finalised even if the candidate never comes back. */
    @Scheduled(fixedDelay = 15_000)
    public void sweep() {
        jdbc.sql("select id from oa_sessions where status = 'FINALIZING' or (status = 'ACTIVE' and ends_at <= now())")
                .query(Long.class).list().forEach(finalizer::finalizeIfReady);
    }

    private JsonNode readJson(String s) {
        try {
            return s == null ? null : json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
