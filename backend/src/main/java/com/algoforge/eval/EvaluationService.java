package com.algoforge.eval;

import com.algoforge.AppProperties;
import com.algoforge.ai.AiOutputs.Review;
import com.algoforge.ai.AiOutputs.TestProposal;
import com.algoforge.ai.AiService;
import com.algoforge.ai.BigO;
import com.algoforge.ai.Disagreements;
import com.algoforge.common.ApiException;
import com.algoforge.judge.CodeRunner;
import com.algoforge.judge.JobQueue;
import com.algoforge.judge.JobQueue.Job;
import com.algoforge.judge.Language;
import com.algoforge.judge.Oracle;
import com.algoforge.judge.Sandbox;
import com.algoforge.judge.Verdicts;
import com.algoforge.judge.Verdicts.Outcome;
import com.algoforge.judge.Verdicts.Verdict;
import com.algoforge.learning.LearningService;
import com.algoforge.problems.ProblemService;
import com.algoforge.problems.ProblemService.JudgeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluation reports. The basic report (static analysis + execution evidence + scoring) is written with every
 * verdict. The deep evaluation runs the section-5 pipeline: AI-proposed and seeded random inputs are validated,
 * answered by the reference solution, run against the submission, and the smallest failure is kept; accepted
 * solutions are benchmarked; then the configured AI provider(s) review the code with all that evidence.
 */
@Service
public class EvaluationService {
    static final int DEEP_BUDGET_SECONDS = 420;
    static final int[] RANDOM_SIZES = {1, 2, 3, 4, 5, 6, 8, 10, 13, 16, 20, 25, 32, 40, 50};
    static final int RANDOM_SEEDS_PER_SIZE = 3;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AppProperties props;
    private final ProblemService problems;
    private final AiService ai;
    private final LearningService learning;
    private final JobQueue queue;

    public EvaluationService(JdbcClient jdbc, ObjectMapper json, AppProperties props, ProblemService problems, AiService ai,
                             LearningService learning, JobQueue queue) {
        this.jdbc = jdbc;
        this.json = json;
        this.props = props;
        this.problems = problems;
        this.ai = ai;
        this.learning = learning;
        this.queue = queue;
    }

    record Sub(long id, long userId, long problemId, String language, String code, String status, Integer passed,
               Integer total, Integer maxTimeMs, Integer maxMemoryKb, Long oaSessionId, String statedComplexity,
               String runtimeVersion, String message) {}

    private Sub sub(long id) {
        return jdbc.sql("""
                select id, user_id, problem_id, language, code, status, passed, total, max_time_ms, max_memory_kb, oa_session_id,
                       stated_complexity, runtime_version, message from submissions where id = ?""")
                .param(id).query(Sub.class).single();
    }

    // ---- basic report -----------------------------------------------------------------------------------------------

    public void createBasicReport(long submissionId) {
        Sub s = sub(submissionId);
        JudgeInfo p = problems.find(s.problemId()).orElseThrow();
        StaticAnalyzer.Result a = StaticAnalyzer.analyze(s.code(), Language.of(s.language()), p.constraintsMd());
        jdbc.sql("""
                insert into complexity_assessments (submission_id, source, time_complexity, space_complexity, explanation, confidence)
                values (?, 'static', ?, ?, ?, ?)
                on conflict (submission_id, source) do update set time_complexity = excluded.time_complexity,
                    space_complexity = excluded.space_complexity, explanation = excluded.explanation, confidence = excluded.confidence""")
                .params(s.id(), a.timeComplexity(), a.spaceComplexity(), a.explanation(), a.confidence()).update();
        ScoringModel.Robustness existing = jdbc.sql("select generated_tests::text from evaluation_reports where submission_id = ?")
                .param(s.id()).query(String.class).optional().map(this::robustnessFrom).orElse(null);
        writeScore(s, p, a, existing, null);
        if ("MEDIUM".equals(a.confidence()) && s.statedComplexity() != null && "ACCEPTED".equals(s.status())) {
            BigO.parse(s.statedComplexity()).ifPresent(claim -> BigO.parse(a.timeComplexity()).ifPresent(est ->
                    learning.recordComplexityClaim(s.id(), claim.sameClass(est))));
        }
    }

    private void writeScore(Sub s, JudgeInfo p, StaticAnalyzer.Result a, ScoringModel.Robustness robustness, String status) {
        int nonSample = jdbc.sql("select count(*) from submission_results where submission_id = ? and kind <> 'SAMPLE' and verdict <> 'SKIPPED'")
                .param(s.id()).query(Integer.class).single();
        Language lang = Language.of(s.language());
        ScoringModel.Score sc = ScoringModel.score(new ScoringModel.Inputs(s.status(), nz(s.passed()), nz(s.total()), nonSample,
                s.maxTimeMs(), p.timeLimitMs() * lang.timeFactor, s.maxMemoryKb(), p.memoryLimitMb() * 1024L, a, robustness),
                props.scoring().weights());
        jdbc.sql("""
                insert into evaluation_reports (submission_id, status, correctness, overall_score, dimensions, findings)
                values (:sid, coalesce(:status, 'BASIC'), :corr, :overall, cast(:dims as jsonb), cast(:findings as jsonb))
                on conflict (submission_id) do update set correctness = excluded.correctness, overall_score = excluded.overall_score,
                    dimensions = excluded.dimensions, findings = excluded.findings,
                    status = coalesce(:status, evaluation_reports.status), updated_at = now()""")
                .param("sid", s.id()).param("status", status).param("corr", sc.correctness()).param("overall", sc.overall())
                .param("dims", write(Map.of("weights", props.scoring().weights(), "items", sc.dimensions())))
                .param("findings", write(a.findings())).update();
    }

    private ScoringModel.Robustness robustnessFrom(String generatedJson) {
        if (generatedJson == null) return null;
        try {
            JsonNode g = json.readTree(generatedJson);
            return new ScoringModel.Robustness(g.path("verifiedPassed").asInt(), g.path("verifiedTotal").asInt());
        } catch (Exception e) {
            return null;
        }
    }

    // ---- deep evaluation: API side ------------------------------------------------------------------------------------

    @Transactional  // a refused job must not leave the report marked DEEP_PENDING
    public long requestDeep(long userId, long submissionId, boolean useAi) {
        record S(String status, String sessionStatus, String mode, String reportStatus) {}
        S s = jdbc.sql("""
                select sub.status, o.status as session_status, d.mode, e.status as report_status from submissions sub
                  left join oa_sessions o on o.id = sub.oa_session_id left join oa_definitions d on d.id = o.definition_id
                  left join evaluation_reports e on e.submission_id = sub.id
                 where sub.id = ? and sub.user_id = ?""").params(submissionId, userId).query(S.class).optional()
                .orElseThrow(() -> ApiException.notFound("Submission"));
        if (s.sessionStatus() != null && !"COMPLETED".equals(s.sessionStatus()) && "ASSESSMENT".equals(s.mode())) {
            throw ApiException.conflict("Evaluations unlock after the assessment ends");
        }
        if (List.of("QUEUED", "COMPILING", "RUNNING").contains(s.status())) throw ApiException.conflict("Wait for the verdict first");
        if (List.of("COMPILATION_ERROR", "INTERNAL_ERROR").contains(s.status())) {
            throw ApiException.badRequest("The submission must compile and run before a deep evaluation");
        }
        if ("DEEP_PENDING".equals(s.reportStatus())) throw ApiException.conflict("A deep evaluation is already running");
        jdbc.sql("update evaluation_reports set status = 'DEEP_PENDING', updated_at = now() where submission_id = ?").param(submissionId).update();
        return queue.enqueue("EVALUATE", userId, submissionId, Map.of("useAi", useAi));
    }

    public void markDeepFailed(long submissionId, String message) {
        jdbc.sql("update evaluation_reports set status = 'DEEP_FAILED', ai_status = ?, updated_at = now() where submission_id = ?")
                .params(message, submissionId).update();
    }

    // ---- deep evaluation: worker side ----------------------------------------------------------------------------------

    public void deepEvaluate(Job job) throws Exception {
        boolean useAi = json.readTree(job.payload()).path("useAi").asBoolean(true);
        Sub s = sub(job.submissionId());
        JudgeInfo p = problems.find(s.problemId()).orElseThrow();
        Language lang = Language.of(s.language());
        StaticAnalyzer.Result analysis = StaticAnalyzer.analyze(s.code(), lang, p.constraintsMd());
        long deadline = System.nanoTime() + DEEP_BUDGET_SECONDS * 1_000_000_000L;
        boolean accepted = "ACCEPTED".equals(s.status());

        Map<String, Object> gen = new LinkedHashMap<>();
        List<Map<String, Object>> cases = new ArrayList<>();
        int[] verified = {0, 0};  // passed, total
        Map<String, Object> counterexample = null;
        Map<String, Object> benchmark = null;
        String aiTestStatus = useAi ? (ai.anyAvailable("TESTGEN") ? null : "NOT_CONFIGURED") : "NOT_REQUESTED";

        try (CodeRunner runner = CodeRunner.open(props.sandbox(), lang, p.memoryLimitMb());
             Oracle oracle = Oracle.open(props.sandbox(), p)) {
            if (!runner.compile(s.code()).ok()) throw new IllegalStateException("submission no longer compiles");
            double tl = lang.timeLimitSeconds(p.timeLimitMs());

            // 1. AI-proposed inputs: validated, answered by the reference, never trusted for their claimed output.
            if (aiTestStatus == null) {
                var call = ai.proposeTests(s.userId(), testgenPrompt(p)).orElseThrow();
                aiTestStatus = call.status();
                if (call.ok()) {
                    for (TestProposal t : call.value()) {
                        if (System.nanoTime() > deadline) break;
                        String source = "ai:" + call.provider() + "/" + call.model();
                        Map<String, Object> c = runCase(runner, oracle, p, lang, tl, s, t.input(), source, t.category(), t.rationale(),
                                t.claimedOutput(), null, verified, true);
                        cases.add(c);
                        if (counterexample == null && "FAILED".equals(c.get("result"))) counterexample = c;
                    }
                }
            }

            // 2. Seeded random differential testing, smallest sizes first, so the first failure is the smallest
            //    counterexample found. All sizes still run so the pass rate is measured, not just the first failure.
            int randomRun = 0, randomFailuresKept = 0;
            if (oracle.canGenerate() && oracle.hasReference()) {
                outer:
                for (int n : RANDOM_SIZES) {
                    if (p.maxN() != null && n > p.maxN()) break;
                    for (int k = 0; k < RANDOM_SEEDS_PER_SIZE; k++) {
                        if (System.nanoTime() > deadline) break outer;
                        long seed = 50_000L + n * 10L + k;
                        String input = oracle.generate(seed, n);
                        randomRun++;
                        Map<String, Object> c = runCase(runner, oracle, p, lang, tl, s, input, "random", "random n=" + n,
                                "Seeded random input (seed " + seed + ")", null, seed, verified, randomFailuresKept < 3);
                        if ("FAILED".equals(c.get("result"))) {
                            if (randomFailuresKept++ < 3) cases.add(c);
                            if (counterexample == null || ((String) counterexample.get("source")).startsWith("ai:")
                                    && ((Integer) c.get("size")) <= ((Integer) counterexample.get("size"))) counterexample = c;
                        }
                    }
                }
            }
            gen.put("randomRun", randomRun);
            gen.put("randomSeedFormula", "seed = 50000 + 10·n + k, k < " + RANDOM_SEEDS_PER_SIZE);

            // 3. Benchmark accepted solutions on growing generated inputs.
            if (accepted && oracle.canGenerate() && p.maxN() != null && System.nanoTime() < deadline) {
                benchmark = benchmark(runner, oracle, p, lang, tl);
                benchmark.put("runtimeVersion", s.runtimeVersion());
            }
        }

        gen.put("aiStatus", aiTestStatus);
        gen.put("verifiedPassed", verified[0]);
        gen.put("verifiedTotal", verified[1]);
        gen.put("unverified", cases.stream().filter(c -> "UNVERIFIED".equals(c.get("verification"))).count());
        gen.put("rejected", cases.stream().filter(c -> "REJECTED".equals(c.get("verification"))).count());
        gen.put("referenceAvailable", p.referencePy() != null);
        gen.put("counterexample", counterexample);
        gen.put("cases", cases.size() > 40 ? cases.subList(0, 40) : cases);

        Double exponent = benchmark != null && Boolean.TRUE.equals(benchmark.get("conclusive")) ? (Double) benchmark.get("exponent") : null;
        if (exponent != null) {
            jdbc.sql("""
                    insert into complexity_assessments (submission_id, source, time_complexity, explanation, confidence, details)
                    values (?, 'empirical', ?, ?, 'MEDIUM', cast(? as jsonb))
                    on conflict (submission_id, source) do update set time_complexity = excluded.time_complexity,
                        explanation = excluded.explanation, details = excluded.details""")
                    .params(s.id(), String.format("≈ n^%.2f", exponent), benchmark.get("label") + ". " + benchmark.get("note"),
                            write(benchmark)).update();
            if (s.statedComplexity() != null) {
                BigO.parse(s.statedComplexity()).ifPresent(g -> learning.recordComplexityClaim(s.id(), Math.abs(g.nPower() - exponent) <= 0.35));
            }
        }

        // 4. AI review with all evidence, then evidence-based disagreement resolution.
        Map<String, Object> aiReview = new LinkedHashMap<>();
        List<Disagreements.Item> disagreements = List.of();
        String aiStatus;
        if (!useAi) {
            aiStatus = "NOT_REQUESTED";
        } else if (!ai.anyAvailable("REVIEW")) {
            aiStatus = "NOT_CONFIGURED";
        } else {
            int failedJudge = nz(s.total()) - nz(s.passed());
            var calls = ai.review(s.userId(), reviewPrompt(p, s, lang, analysis, verified, counterexample, benchmark));
            List<Disagreements.ProviderReview> ok = new ArrayList<>();
            List<String> statuses = new ArrayList<>();
            for (var c : calls) {
                statuses.add(c.provider() + ": " + c.status());
                aiReview.put(c.provider(), c.ok() ? Map.of("model", c.model(), "review", c.value(), "status", c.status())
                        : Map.of("model", c.model(), "status", c.status(), "error", c.error()));
                if (c.ok()) {
                    ok.add(new Disagreements.ProviderReview(c.provider(), c.value()));
                    Review r = c.value();
                    jdbc.sql("""
                            insert into complexity_assessments (submission_id, source, time_complexity, space_complexity, explanation, confidence)
                            values (?, ?, ?, ?, ?, 'LOW')
                            on conflict (submission_id, source) do update set time_complexity = excluded.time_complexity,
                                space_complexity = excluded.space_complexity, explanation = excluded.explanation""")
                            .params(s.id(), "ai:" + c.provider(), r.timeComplexity(), r.spaceComplexity(), r.complexityExplanation()).update();
                }
            }
            aiStatus = String.join("; ", statuses);
            disagreements = Disagreements.resolve(ok, new Disagreements.Evidence(accepted, nz(s.total()), failedJudge,
                    verified[1], verified[1] - verified[0], analysis.timeComplexity(), analysis.confidence(),
                    analysis.spaceComplexity(), exponent));
        }

        jdbc.sql("""
                update evaluation_reports set generated_tests = cast(? as jsonb), benchmark = cast(? as jsonb),
                       ai_review = cast(? as jsonb), ai_disagreements = cast(? as jsonb), ai_status = ?, updated_at = now()
                 where submission_id = ?""")
                .params(write(gen), benchmark == null ? null : write(benchmark), aiReview.isEmpty() ? null : write(aiReview),
                        write(disagreements), aiStatus, s.id()).update();
        writeScore(s, p, analysis, new ScoringModel.Robustness(verified[0], verified[1]), "DEEP_READY");
    }

    /** Validates one candidate input, gets the reference answer, runs the submission, and stores the test. */
    private Map<String, Object> runCase(CodeRunner runner, Oracle oracle, JudgeInfo p, Language lang, double tl, Sub s,
                                        String input, String source, String category, String rationale, String claimed,
                                        Long seed, int[] verified, boolean store) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("source", source);
        c.put("category", category);
        c.put("rationale", rationale);
        Optional<String> invalid = oracle.validate(input);
        if (invalid.isPresent()) {
            c.put("verification", "REJECTED");
            c.put("reason", "Input violates the constraints: " + invalid.get());
            return c;
        }
        String expected = oracle.hasReference() ? oracle.reference(input) : null;
        Sandbox.Exec e = runner.run(input, tl, p.memoryLimitMb());
        Outcome o = Verdicts.classify(e, tl, p.memoryLimitMb() * 1024L, lang, props.sandbox().outputLimitKb(), expected);
        c.put("verification", expected != null ? "REFERENCE" : "UNVERIFIED");
        c.put("verdict", o.verdict().name());
        if (expected != null) {
            verified[1]++;
            if (o.verdict() == Verdict.ACCEPTED) verified[0]++;
        }
        boolean failed = o.verdict() != Verdict.ACCEPTED;
        c.put("result", failed ? "FAILED" : expected == null ? "RAN" : "PASSED");
        if (o.message() != null) c.put("message", o.message());
        c.put("size", input.length());
        // Passing seeded random cases are reproducible from their seed, so only failures and AI cases are stored.
        if (store && (failed || seed == null)) {
            long id = jdbc.sql("""
                    insert into test_cases (problem_id, kind, ordinal, input, expected_output, verification, provenance,
                        generator_version, seed, category, owner_id)
                    values (?, 'GENERATED', 0, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(p.id(), input, expected != null ? expected : claimed, expected != null ? "REFERENCE" : "UNVERIFIED",
                            source + (expected != null ? ", answer from reference solution" : ", answer claimed by the model (unverified)"),
                            seed != null ? oracle.generatorVersion() : null, seed, category, s.userId())
                    .query(Long.class).single();
            c.put("testId", id);
        }
        if (failed) {
            c.put("input", CodeRunner.excerpt(input, 2000));
            c.put("expected", expected == null ? null : CodeRunner.excerpt(expected, 2000));
            c.put("actual", CodeRunner.excerpt(e.stdout(), 2000));
        }
        return c;
    }

    private Map<String, Object> benchmark(CodeRunner runner, Oracle oracle, JudgeInfo p, Language lang, double tl) {
        int max = p.maxN();
        int[] sizes = Arrays.stream(new int[] {max / 16, max / 8, max / 4, max / 2, max}).filter(n -> n >= 1).distinct().toArray();
        double baseline = median(runner, oracle.generate(9000, 1), tl, p, lang, new double[3]);
        List<Map<String, Object>> points = new ArrayList<>();
        List<double[]> usable = new ArrayList<>();
        double worstSpread = 0;
        String stop = null;
        for (int n : sizes) {
            double[] runs = new double[3];
            double med = median(runner, oracle.generate(9001, n), tl, p, lang, runs);
            if (med < 0) { stop = "exceeded the time limit at n = " + n; break; }
            double spread = (runs[2] - runs[0]) / Math.max(med, 1e-3);
            worstSpread = Math.max(worstSpread, spread);
            points.add(Map.of("n", n, "medianMs", Math.round(med * 1000), "minMs", Math.round(runs[0] * 1000),
                    "maxMs", Math.round(runs[2] * 1000)));
            if (med - baseline >= 0.02) usable.add(new double[] {Math.log(n), Math.log(med - baseline)});
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("baselineMs", Math.round(baseline * 1000));
        b.put("points", points);
        b.put("inputSizePower", p.inputSizePower());
        boolean conclusive = usable.size() >= 3 && worstSpread <= 0.3 && stop == null;
        b.put("conclusive", conclusive);
        if (usable.size() >= 2) {
            double e = Math.round(slope(usable) * 100) / 100.0;
            b.put("exponent", e);
            String cls = e < 0.3 ? "≈ constant" : e < 1.35 ? "≈ linear or n log n" : e < 1.75 ? "≈ n^1.5" : e < 2.35 ? "≈ quadratic" : "≈ cubic or worse";
            b.put("label", String.format("measured growth n^%.2f (%s in the generator size n%s)", e, cls,
                    p.inputSizePower() > 1 ? "; input size grows as n^" + p.inputSizePower() : ""));
        }
        b.put("note", (stop != null ? "Stopped: " + stop + ". " : "")
                + (conclusive ? "" : usable.isEmpty() && stop == null
                        ? "Inconclusive: every run finished within 20 ms of start-up time even at n = " + max
                          + ", too fast to measure growth (consistent with an efficient solution). "
                        : "Inconclusive: fewer than 3 sizes above the 20 ms noise floor, or run-to-run spread above 30%. ")
                + "Median of 3 runs per size after subtracting start-up time (" + Math.round(baseline * 1000)
                + " ms). Absolute times depend on this worker's hardware and are not comparable across machines.");
        return b;
    }

    /** Median of three runs in seconds (sorted into {@code runs}), or -1 if any run exceeded the time limit. */
    private double median(CodeRunner runner, String input, double tl, JudgeInfo p, Language lang, double[] runs) {
        for (int i = 0; i < 3; i++) {
            Sandbox.Exec e = runner.run(input, tl, p.memoryLimitMb());
            if (e.wallSeconds() >= tl - 0.005) return -1;
            runs[i] = e.wallSeconds();
        }
        Arrays.sort(runs);
        return runs[1];
    }

    static double slope(List<double[]> pts) {
        double mx = pts.stream().mapToDouble(x -> x[0]).average().orElse(0), my = pts.stream().mapToDouble(x -> x[1]).average().orElse(0);
        double num = 0, den = 0;
        for (double[] q : pts) { num += (q[0] - mx) * (q[1] - my); den += (q[0] - mx) * (q[0] - mx); }
        return den == 0 ? 0 : num / den;
    }

    // ---- prompts ------------------------------------------------------------------------------------------------------

    private String testgenPrompt(JudgeInfo p) {
        StringBuilder sb = new StringBuilder("## Problem: ").append(p.title()).append("\n")
                .append(cap(p.statementMd(), 12000)).append("\n\n## Constraints\n").append(nullToEmpty(p.constraintsMd())).append("\n\n");
        List<Map<String, Object>> samples = jdbc.sql("select input, expected_output from test_cases where problem_id = ? and kind = 'SAMPLE' order by ordinal")
                .param(p.id()).query().listOfRows();
        for (Map<String, Object> sm : samples) {
            sb.append("### Sample input\n```\n").append(cap((String) sm.get("input"), 2000)).append("```\n### Sample output\n```\n")
                    .append(cap((String) sm.get("expected_output"), 2000)).append("```\n");
        }
        return sb.append("\nPropose up to 8 test cases.").toString();
    }

    private String reviewPrompt(JudgeInfo p, Sub s, Language lang, StaticAnalyzer.Result a, int[] verified,
                                Map<String, Object> counterexample, Map<String, Object> benchmark) {
        StringBuilder sb = new StringBuilder("## Problem: ").append(p.title()).append("\n").append(cap(p.statementMd(), 12000))
                .append("\n\n## Constraints\n").append(nullToEmpty(p.constraintsMd()))
                .append("\nTime limit ").append(p.timeLimitMs()).append(" ms, memory limit ").append(p.memoryLimitMb()).append(" MB\n\n")
                .append("## Submission (").append(lang.id).append(")\n```\n").append(cap(s.code(), 40000)).append("\n```\n\n")
                .append("## Execution evidence (authoritative)\n")
                .append("- Verdict: ").append(s.status()).append(", passed ").append(nz(s.passed())).append("/").append(nz(s.total())).append(" judge tests\n");
        if (s.message() != null) sb.append("- First failure: ").append(s.message()).append("\n");
        if (s.maxTimeMs() != null) sb.append("- Slowest test ").append(s.maxTimeMs()).append(" ms; peak memory ")
                .append(s.maxMemoryKb() == null ? "?" : s.maxMemoryKb() / 1024).append(" MB\n");
        sb.append("- Generated tests checked against the reference solution: ").append(verified[0]).append("/").append(verified[1]).append(" passed\n");
        if (counterexample != null) sb.append("- Smallest failing generated input:\n```\n").append(counterexample.get("input"))
                .append("\n```\n  expected: ").append(counterexample.get("expected")).append("\n  got: ").append(counterexample.get("actual")).append("\n");
        sb.append("- Static heuristic estimate (confidence ").append(a.confidence()).append("): time ").append(a.timeComplexity())
                .append(", space ").append(a.spaceComplexity()).append("\n");
        if (benchmark != null && benchmark.get("label") != null) {
            sb.append("- Benchmark: ").append(benchmark.get("label")).append(Boolean.TRUE.equals(benchmark.get("conclusive")) ? "" : " (inconclusive)").append("\n");
        }
        return sb.toString();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cap(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "\n…"; }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static int nz(Integer i) { return i == null ? 0 : i; }
}
