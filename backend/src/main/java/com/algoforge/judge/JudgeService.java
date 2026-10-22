package com.algoforge.judge;

import com.algoforge.AppProperties;
import com.algoforge.eval.EvaluationService;
import com.algoforge.judge.JobQueue.Job;
import com.algoforge.judge.Verdicts.Outcome;
import com.algoforge.judge.Verdicts.Verdict;
import com.algoforge.learning.LearningService;
import com.algoforge.oa.OaFinalizer;
import com.algoforge.problems.ProblemService;
import com.algoforge.problems.ProblemService.JudgeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Worker-side judging: compiles and runs real code in the sandbox; verdicts come only from execution. */
@Service
public class JudgeService {
    static final int JUDGE_BUDGET_SECONDS = 180;
    static final int EXCERPT = 1000;

    private final JdbcClient jdbc;
    private final ProblemService problems;
    private final AppProperties props;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final EvaluationService evaluations;
    private final LearningService learning;
    private final OaFinalizer oa;
    private final Map<Language, String> versions = new ConcurrentHashMap<>();

    public JudgeService(JdbcClient jdbc, ProblemService problems, AppProperties props, ObjectMapper json,
                        TransactionTemplate tx, EvaluationService evaluations, LearningService learning, OaFinalizer oa) {
        this.jdbc = jdbc;
        this.problems = problems;
        this.props = props;
        this.json = json;
        this.tx = tx;
        this.evaluations = evaluations;
        this.learning = learning;
        this.oa = oa;
    }

    record SubmissionRow(long id, long userId, long problemId, String language, String code, Long oaSessionId) {}

    public record TestRow(long id, String kind, int ordinal, String input, String expectedOutput) {}

    public record TestResult(int ordinal, Long testCaseId, String kind, Verdict verdict, Integer timeMs, Long memoryKb,
                             String outputExcerpt, String errorExcerpt, String message) {}

    public void judge(Job job) {
        SubmissionRow s = jdbc.sql("select id, user_id, problem_id, language, code, oa_session_id from submissions where id = ?")
                .param(job.submissionId()).query(SubmissionRow.class).single();
        JudgeInfo p = problems.find(s.problemId()).orElseThrow();
        ensureOfficialTests(p);
        List<TestRow> tests = officialTests(p.id());
        Language lang = Language.of(s.language());
        setStatus(s.id(), "COMPILING");

        List<TestResult> results = new ArrayList<>();
        String version;
        try (CodeRunner runner = CodeRunner.open(props.sandbox(), lang, p.memoryLimitMb())) {
            version = versions.computeIfAbsent(lang, l -> runner.version());
            CodeRunner.Compile c = runner.compile(s.code());
            if (!c.ok()) {
                finish(s, "COMPILATION_ERROR", List.of(), c.output(), "Compilation failed", version);
                return;
            }
            setStatus(s.id(), "RUNNING");
            double tl = lang.timeLimitSeconds(p.timeLimitMs());
            long deadline = System.nanoTime() + JUDGE_BUDGET_SECONDS * 1_000_000_000L;
            for (TestRow t : tests) {
                if (System.nanoTime() > deadline) {
                    results.add(new TestResult(t.ordinal(), t.id(), t.kind(), Verdict.SKIPPED, null, null, null, null,
                            "Not run: judging time budget exhausted by earlier time-outs"));
                    continue;
                }
                Sandbox.Exec e = runner.run(t.input(), tl, p.memoryLimitMb());
                Outcome o = Verdicts.classify(e, tl, p.memoryLimitMb() * 1024L, lang, props.sandbox().outputLimitKb(), t.expectedOutput());
                boolean tle = o.verdict() == Verdict.TIME_LIMIT_EXCEEDED;
                results.add(new TestResult(t.ordinal(), t.id(), t.kind(), o.verdict(), (int) Math.round(e.wallSeconds() * 1000),
                        tle ? null : e.maxRssKb(), CodeRunner.excerpt(e.stdout(), EXCERPT),
                        CodeRunner.excerpt(e.stderr(), EXCERPT), o.message()));
            }
        }
        String overall = Verdicts.overall(results.stream().map(TestResult::verdict).toList());
        String message = results.stream().filter(r -> r.verdict() != Verdict.ACCEPTED).findFirst()
                .map(r -> r.verdict().name().replace('_', ' ').toLowerCase() + " on test " + (results.indexOf(r) + 1)
                        + (r.message() == null ? "" : ": " + r.message()))
                .orElse(null);
        finish(s, overall, results, null, message, version);
    }

    private void finish(SubmissionRow s, String status, List<TestResult> results, String compileOutput, String message, String version) {
        long passed = results.stream().filter(r -> r.verdict() == Verdict.ACCEPTED).count();
        Integer maxTime = results.stream().map(TestResult::timeMs).filter(x -> x != null).max(Integer::compare).orElse(null);
        Long maxMem = results.stream().map(TestResult::memoryKb).filter(x -> x != null).max(Long::compare).orElse(null);
        tx.executeWithoutResult(t -> {
            jdbc.sql("delete from submission_results where submission_id = ?").param(s.id()).update();
            for (TestResult r : results) {
                jdbc.sql("""
                        insert into submission_results (submission_id, ordinal, test_case_id, kind, verdict, time_ms, memory_kb,
                            output_excerpt, error_excerpt) values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(s.id(), r.ordinal(), r.testCaseId(), r.kind(), r.verdict().name(), r.timeMs(),
                                r.memoryKb() == null ? null : r.memoryKb().intValue(), r.outputExcerpt(), r.errorExcerpt())
                        .update();
            }
            jdbc.sql("""
                    update submissions set status = ?, passed = ?, total = ?, max_time_ms = ?, max_memory_kb = ?,
                           compile_output = ?, message = ?, runtime_version = ?, judged_at = now() where id = ?""")
                    .params(status, (int) passed, results.isEmpty() ? null : results.size(), maxTime,
                            maxMem == null ? null : maxMem.intValue(), compileOutput, message, version, s.id())
                    .update();
            jdbc.sql("""
                    insert into user_problem_state (user_id, problem_id, status, solved_at)
                    values (:u, :p, case when :ok then 'SOLVED' else 'ATTEMPTED' end, case when :ok then now() end)
                    on conflict (user_id, problem_id) do update set
                        status = case when :ok then 'SOLVED'
                                      when user_problem_state.status in ('SOLVED', 'NEEDS_REVISION') then user_problem_state.status
                                      else 'ATTEMPTED' end,
                        solved_at = coalesce(user_problem_state.solved_at, excluded.solved_at), updated_at = now()""")
                    .param("u", s.userId()).param("p", s.problemId()).param("ok", "ACCEPTED".equals(status)).update();
            evaluations.createBasicReport(s.id());
            learning.recordJudged(s.id());
        });
        if (s.oaSessionId() != null) oa.finalizeIfReady(s.oaSessionId());
    }

    public void markInternalError(long submissionId, String message) {
        jdbc.sql("update submissions set status = 'INTERNAL_ERROR', message = ?, judged_at = now() where id = ? and judged_at is null")
                .params(message, submissionId).update();
        jdbc.sql("select oa_session_id from submissions where id = ?").param(submissionId).query(Long.class).optional()
                .ifPresent(oa::finalizeIfReady);
    }

    private void setStatus(long submissionId, String status) {
        jdbc.sql("update submissions set status = ? where id = ?").params(status, submissionId).update();
    }

    public List<TestRow> officialTests(long problemId) {
        return jdbc.sql("""
                select id, kind, ordinal, input, expected_output from test_cases
                 where problem_id = ? and kind in ('SAMPLE', 'OFFICIAL') order by kind = 'OFFICIAL', ordinal""")
                .param(problemId).query(TestRow.class).list();
    }

    /** Materialises a problem's generator-specified official tests once (inputs from gen.py, answers from ref.py). */
    public void ensureOfficialTests(JudgeInfo p) {
        List<long[]> specs = new ArrayList<>();
        try {
            for (JsonNode s : json.readTree(p.generatedSpecs())) specs.add(new long[] {s.get("seed").asLong(), s.get("n").asLong()});
        } catch (Exception e) {
            throw new IllegalStateException("bad generated_specs for problem " + p.id(), e);
        }
        if (specs.isEmpty() || p.generatorPy() == null || p.referencePy() == null) return;
        tx.executeWithoutResult(t -> {
            jdbc.sql("select pg_advisory_xact_lock(?)").param(p.id()).query((rs, i) -> 0).list();
            Set<Long> have = new HashSet<>(jdbc.sql("select seed from test_cases where problem_id = ? and kind = 'OFFICIAL' and seed is not null")
                    .param(p.id()).query(Long.class).list());
            List<long[]> missing = specs.stream().filter(s -> !have.contains(s[0])).toList();
            if (missing.isEmpty()) return;
            try (Oracle oracle = Oracle.open(props.sandbox(), p)) {
                for (long[] spec : missing) {
                    String input = oracle.generate(spec[0], (int) spec[1]);
                    oracle.validate(input).ifPresent(msg -> {
                        throw new IllegalStateException("generator produced invalid input for seed " + spec[0] + ": " + msg);
                    });
                    String expected = oracle.reference(input);
                    jdbc.sql("""
                            insert into test_cases (problem_id, kind, ordinal, input, expected_output, verification, provenance,
                                generator_version, seed) values (?, 'OFFICIAL', ?, ?, ?, 'REFERENCE', ?, ?, ?)""")
                            .params(p.id(), 1000 + (int) spec[0], input, expected,
                                    "generator seed=" + spec[0] + " n=" + spec[1] + ", answer from reference solution",
                                    oracle.generatorVersion(), spec[0])
                            .update();
                }
            }
        });
    }

    /** Custom-input or sample runs: execution only, nothing is stored except the job result. */
    public Map<String, Object> run(Job job) throws Exception {
        JsonNode payload = json.readTree(job.payload());
        JudgeInfo p = problems.find(payload.get("problemId").asLong()).orElseThrow();
        Language lang = Language.of(payload.get("language").asText());
        Map<String, Object> result = new LinkedHashMap<>();
        try (CodeRunner runner = CodeRunner.open(props.sandbox(), lang, p.memoryLimitMb())) {
            CodeRunner.Compile c = runner.compile(payload.get("code").asText());
            result.put("compile", Map.of("ok", c.ok(), "output", c.output()));
            if (!c.ok()) return result;
            double tl = lang.timeLimitSeconds(p.timeLimitMs());
            List<Map<String, Object>> runs = new ArrayList<>();
            for (JsonNode cs : payload.get("cases")) {
                String expected = cs.hasNonNull("expected") ? cs.get("expected").asText() : null;
                Sandbox.Exec e = runner.run(cs.get("input").asText(), tl, p.memoryLimitMb());
                Outcome o = Verdicts.classify(e, tl, p.memoryLimitMb() * 1024L, lang, props.sandbox().outputLimitKb(), expected);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("verdict", o.verdict().name());
                if (o.message() != null) r.put("message", o.message());
                r.put("timeMs", Math.round(e.wallSeconds() * 1000));
                if (o.verdict() != Verdict.TIME_LIMIT_EXCEEDED) r.put("memoryKb", e.maxRssKb());
                r.put("stdout", CodeRunner.excerpt(e.stdout(), 64_000));
                r.put("stderr", CodeRunner.excerpt(e.stderr(), 8_000));
                if (expected != null) r.put("expected", CodeRunner.excerpt(expected, 64_000));
                runs.add(r);
            }
            result.put("runs", runs);
            result.put("runtimeVersion", versions.computeIfAbsent(lang, l -> runner.version()));
        }
        return result;
    }
}
