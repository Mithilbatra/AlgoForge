package com.algoforge.judge;

import com.algoforge.AppProperties;
import com.algoforge.auth.CurrentUser;
import com.algoforge.common.ApiException;
import com.algoforge.common.RateLimiter;
import com.algoforge.problems.ProblemService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class SubmissionController {
    private final JdbcClient jdbc;
    private final JobQueue queue;
    private final ProblemService problems;
    private final RateLimiter limiter;
    private final AppProperties props;
    private final ObjectMapper json;

    public SubmissionController(JdbcClient jdbc, JobQueue queue, ProblemService problems, RateLimiter limiter,
                                AppProperties props, ObjectMapper json) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.problems = problems;
        this.limiter = limiter;
        this.props = props;
        this.json = json;
    }

    public record SubmitRequest(@NotNull Long problemId, @NotBlank @Pattern(regexp = "java|cpp|python") String language,
                                @NotBlank @Size(max = 65536) String code, @Size(max = 40) String statedComplexity,
                                Long oaSessionId) {}

    public record Queued(Long submissionId, long jobId) {}

    @PostMapping("/api/submissions")
    @Transactional
    public Queued submit(@Valid @RequestBody SubmitRequest body, Authentication auth) {
        long uid = CurrentUser.id(auth);
        limiter.check("submit:" + uid, props.limits().submissionsPerMinute(), Duration.ofMinutes(1));
        problems.visible(uid, body.problemId());
        if (!problems.judgeable(body.problemId())) {
            throw ApiException.badRequest("This problem has no tests in AlgoForge. Use Run with custom input, and submit on the original platform.");
        }
        if (body.oaSessionId() != null) {
            checkAssessmentSubmission(uid, body);
        } else if (problems.lockedByAssessment(uid, body.problemId())) {
            throw ApiException.conflict("This problem is part of your running assessment; submit it from the assessment page");
        }
        long sid = jdbc.sql("""
                insert into submissions (user_id, problem_id, oa_session_id, language, code, stated_complexity)
                values (?, ?, ?, ?, ?, ?) returning id""")
                .params(uid, body.problemId(), body.oaSessionId(), body.language(), body.code(),
                        blankToNull(body.statedComplexity()))
                .query(Long.class).single();
        long jobId = queue.enqueue("JUDGE", uid, sid, Map.of());
        return new Queued(sid, jobId);
    }

    /** Server-authoritative: the deadline is compared with the database clock, never the client's. */
    private void checkAssessmentSubmission(long uid, SubmitRequest body) {
        record S(String status, boolean open, String[] languages) {}
        S s = jdbc.sql("""
                select s.status, now() < s.ends_at as open, d.languages from oa_sessions s
                  join oa_definitions d on d.id = s.definition_id where s.id = ? and s.user_id = ? for update of s""")
                .params(body.oaSessionId(), uid)
                .query((rs, i) -> new S(rs.getString(1), rs.getBoolean(2), (String[]) rs.getArray(3).getArray()))
                .optional().orElseThrow(() -> ApiException.notFound("Assessment session"));
        if (!"ACTIVE".equals(s.status()) || !s.open()) throw ApiException.conflict("The assessment has ended; submissions are closed");
        if (!List.of(s.languages()).contains(body.language())) throw ApiException.badRequest("Language not allowed in this assessment");
        boolean inSession = jdbc.sql("""
                select count(*) from oa_sessions s join oa_problems op on op.definition_id = s.definition_id
                 where s.id = ? and op.problem_id = ?""").params(body.oaSessionId(), body.problemId()).query(Long.class).single() > 0;
        if (!inSession) throw ApiException.badRequest("Problem is not part of this assessment");
    }

    public record RunRequest(@NotNull Long problemId, @NotBlank @Pattern(regexp = "java|cpp|python") String language,
                             @NotBlank @Size(max = 65536) String code, @Size(max = 1_000_000) String input, boolean samples) {}

    /** Runs code on custom input or on the visible samples. Nothing is judged or recorded. */
    @PostMapping("/api/runs")
    public Queued run(@Valid @RequestBody RunRequest body, Authentication auth) {
        long uid = CurrentUser.id(auth);
        limiter.check("run:" + uid, props.limits().runsPerMinute(), Duration.ofMinutes(1));
        problems.visible(uid, body.problemId());
        List<Map<String, Object>> cases = new ArrayList<>();
        if (body.samples()) {
            jdbc.sql("select input, expected_output from test_cases where problem_id = ? and kind = 'SAMPLE' order by ordinal")
                    .param(body.problemId())
                    .query((rs, i) -> Map.<String, Object>of("input", rs.getString(1), "expected", rs.getString(2)))
                    .list().forEach(cases::add);
            if (cases.isEmpty()) throw ApiException.badRequest("This problem has no sample tests; use custom input");
        } else {
            cases.add(Map.of("input", body.input() == null ? "" : body.input()));
        }
        long jobId = queue.enqueue("RUN", uid, null, Map.of("problemId", body.problemId(), "language", body.language(),
                "code", body.code(), "cases", cases));
        return new Queued(null, jobId);
    }

    public record JobView(long id, String type, String status, JsonNode result, String error, Integer queuePosition) {}

    @GetMapping("/api/jobs/{id}")
    public JobView job(@PathVariable long id, Authentication auth) {
        return jdbc.sql("""
                select j.id, j.type, j.status, j.result::text as result, j.error,
                       case when j.status = 'QUEUED' then (select count(*)::int from execution_jobs q where q.status = 'QUEUED' and q.id < j.id) end as pos
                  from execution_jobs j where j.id = ? and j.user_id = ?""")
                .params(id, CurrentUser.id(auth))
                .query((rs, i) -> new JobView(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                        readJson(rs.getString("result")), rs.getString("error"), (Integer) rs.getObject("pos")))
                .optional().orElseThrow(() -> ApiException.notFound("Job"));
    }

    public record SubmissionSummary(long id, long problemId, String problemTitle, String language, String status,
                                    Integer passed, Integer total, Integer maxTimeMs, Integer maxMemoryKb,
                                    Long oaSessionId, OffsetDateTime createdAt) {}

    @GetMapping("/api/submissions")
    public List<SubmissionSummary> history(Authentication auth, @RequestParam(required = false) Long problemId,
                                           @RequestParam(defaultValue = "50") @Max(200) int limit) {
        return jdbc.sql("""
                select s.id, s.problem_id, p.title as problem_title, s.language, s.status, s.passed, s.total, s.max_time_ms,
                       s.max_memory_kb, s.oa_session_id, s.created_at
                  from submissions s join problems p on p.id = s.problem_id
                 where s.user_id = :uid and (cast(:pid as bigint) is null or s.problem_id = :pid)
                 order by s.id desc limit :limit""")
                .param("uid", CurrentUser.id(auth)).param("pid", problemId).param("limit", Math.min(limit, 200))
                .query(SubmissionSummary.class).list();
    }

    @GetMapping("/api/submissions/{id}")
    public Map<String, Object> detail(@PathVariable long id, Authentication auth) {
        long uid = CurrentUser.id(auth);
        List<Map<String, Object>> found = jdbc.sql("""
                select s.id, s.problem_id as "problemId", p.title as "problemTitle", s.language, s.code, s.status, s.passed, s.total,
                       s.max_time_ms as "maxTimeMs", s.max_memory_kb as "maxMemoryKb", s.compile_output as "compileOutput",
                       s.message, s.runtime_version as "runtimeVersion", s.stated_complexity as "statedComplexity",
                       s.oa_session_id as "oaSessionId", s.created_at as "createdAt", s.judged_at as "judgedAt",
                       p.time_limit_ms as "timeLimitMs", p.memory_limit_mb as "memoryLimitMb",
                       (select o.status from oa_sessions o where o.id = s.oa_session_id) as "sessionStatus",
                       (select d.mode from oa_sessions o join oa_definitions d on d.id = o.definition_id where o.id = s.oa_session_id) as "sessionMode",
                       (select e.id from evaluation_reports e where e.submission_id = s.id) as "evaluationId"
                  from submissions s join problems p on p.id = s.problem_id where s.id = ? and s.user_id = ?""")
                .params(id, uid).query().listOfRows();
        if (found.isEmpty()) throw ApiException.notFound("Submission");
        Map<String, Object> s = found.getFirst();
        // During a running assessment only verdicts are visible; hidden test data, outputs and evaluations come after it ends.
        boolean hideDetails = s.get("sessionStatus") != null && !"COMPLETED".equals(s.get("sessionStatus"))
                && "ASSESSMENT".equals(s.get("sessionMode"));
        List<Map<String, Object>> tests = jdbc.sql("""
                select r.ordinal, r.kind, r.verdict, r.time_ms as "timeMs", r.memory_kb as "memoryKb",
                       r.output_excerpt as "outputExcerpt", r.error_excerpt as "errorExcerpt",
                       left(t.input, 2000) as input, left(t.expected_output, 2000) as expected, length(t.input) as "inputLength",
                       t.provenance
                  from submission_results r left join test_cases t on t.id = r.test_case_id
                 where r.submission_id = ? order by r.ordinal""")
                .param(id).query().listOfRows();
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> t : tests) {
            Map<String, Object> row = new LinkedHashMap<>(t);
            if (hideDetails && !"SAMPLE".equals(t.get("kind"))) {
                row.keySet().retainAll(List.of("ordinal", "kind", "verdict", "timeMs", "memoryKb"));
            } else if (!"SAMPLE".equals(t.get("kind")) && "ACCEPTED".equals(t.get("verdict"))) {
                row.keySet().removeAll(List.of("input", "expected", "outputExcerpt", "errorExcerpt"));  // shown only when it helps debug
            }
            visible.add(row);
        }
        if (hideDetails) s.remove("evaluationId");
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.put("tests", visible);
        out.put("detailsHidden", hideDetails);
        return out;
    }

    private JsonNode readJson(String s) {
        try {
            return s == null ? null : json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
