package com.algoforge.eval;

import com.algoforge.ai.AiService;
import com.algoforge.auth.CurrentUser;
import com.algoforge.common.ApiException;
import com.algoforge.common.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!worker")
public class EvaluationController {
    private final JdbcClient jdbc;
    private final EvaluationService evaluations;
    private final AiService ai;
    private final RateLimiter limiter;
    private final ObjectMapper json;

    public EvaluationController(JdbcClient jdbc, EvaluationService evaluations, AiService ai, RateLimiter limiter, ObjectMapper json) {
        this.jdbc = jdbc;
        this.evaluations = evaluations;
        this.ai = ai;
        this.limiter = limiter;
        this.json = json;
    }

    public record EvaluationSummary(long id, long submissionId, long problemId, String problemTitle, String language,
                                    String verdict, String status, String correctness, double overallScore, OffsetDateTime updatedAt) {}

    // Evaluations of assessment submissions stay hidden until that assessment is completed.
    private static final String VISIBLE = """
            (s.oa_session_id is null or exists (select 1 from oa_sessions o join oa_definitions d on d.id = o.definition_id
              where o.id = s.oa_session_id and (o.status = 'COMPLETED' or d.mode = 'PRACTICE')))""";

    @GetMapping("/api/evaluations")
    public List<EvaluationSummary> list(Authentication auth) {
        return jdbc.sql("""
                select e.id, s.id as submission_id, p.id as problem_id, p.title as problem_title, s.language, s.status as verdict,
                       e.status, e.correctness, e.overall_score, e.updated_at
                  from evaluation_reports e join submissions s on s.id = e.submission_id join problems p on p.id = s.problem_id
                 where s.user_id = ? and """ + VISIBLE + " order by e.id desc limit 100")
                .param(CurrentUser.id(auth)).query(EvaluationSummary.class).list();
    }

    @GetMapping("/api/evaluations/{id}")
    public Map<String, Object> detail(@PathVariable long id, Authentication auth) {
        List<Map<String, Object>> rows = jdbc.sql("""
                select e.id, e.submission_id as "submissionId", e.status, e.correctness, e.overall_score as "overallScore",
                       e.dimensions::text as dimensions, e.findings::text as findings, e.generated_tests::text as "generatedTests",
                       e.benchmark::text as benchmark, e.ai_review::text as "aiReview", e.ai_disagreements::text as "aiDisagreements",
                       e.ai_status as "aiStatus", e.updated_at as "updatedAt", s.code, s.language, s.status as verdict,
                       s.passed, s.total, s.max_time_ms as "maxTimeMs", s.max_memory_kb as "maxMemoryKb",
                       s.runtime_version as "runtimeVersion", s.stated_complexity as "statedComplexity",
                       p.id as "problemId", p.title as "problemTitle", p.time_limit_ms as "timeLimitMs", p.memory_limit_mb as "memoryLimitMb"
                  from evaluation_reports e join submissions s on s.id = e.submission_id join problems p on p.id = s.problem_id
                 where e.id = ? and s.user_id = ? and """ + VISIBLE)
                .params(id, CurrentUser.id(auth)).query().listOfRows();
        if (rows.isEmpty()) throw ApiException.notFound("Evaluation");
        Map<String, Object> out = new LinkedHashMap<>(rows.getFirst());
        for (String k : List.of("dimensions", "findings", "generatedTests", "benchmark", "aiReview", "aiDisagreements")) {
            out.put(k, parse((String) out.get(k)));
        }
        out.put("complexity", jdbc.sql("""
                select source, time_complexity as "timeComplexity", space_complexity as "spaceComplexity", explanation, confidence
                  from complexity_assessments where submission_id = ? order by source""")
                .param(out.get("submissionId")).query().listOfRows());
        return out;
    }

    public record DeepRequest(boolean useAi) {}

    @PostMapping("/api/submissions/{id}/evaluate")
    public Map<String, Object> deep(@PathVariable long id, @RequestBody DeepRequest body, Authentication auth) {
        long uid = CurrentUser.id(auth);
        limiter.check("deep:" + uid, 10, Duration.ofHours(1));
        return Map.of("jobId", evaluations.requestDeep(uid, id, body.useAi()));
    }

    @GetMapping("/api/ai/status")
    public Map<String, Object> aiStatus() {
        return Map.of("providers", ai.status(), "dualMode", ai.dualMode());
    }

    private JsonNode parse(String s) {
        try {
            return s == null ? null : json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
