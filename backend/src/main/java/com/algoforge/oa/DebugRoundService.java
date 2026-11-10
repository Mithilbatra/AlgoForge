package com.algoforge.oa;

import com.algoforge.AppProperties;
import com.algoforge.judge.CodeRunner;
import com.algoforge.judge.JobQueue;
import com.algoforge.judge.JudgeService;
import com.algoforge.judge.JudgeService.TestRow;
import com.algoforge.judge.Language;
import com.algoforge.judge.Sandbox;
import com.algoforge.judge.Verdicts;
import com.algoforge.judge.Verdicts.Verdict;
import com.algoforge.problems.ProblemService;
import com.algoforge.problems.ProblemService.JudgeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Debugging rounds. Each new round plants fresh bugs in the reference solution of every problem: one-token changes (a
 * comparison, an arithmetic or boolean operator, a small constant, a loop bound, an index, min for max) listed by
 * debug/plant_bugs.py, each on its own line and kept only when some test catches it. Fixing them restores the
 * reference solution, so a round is always solvable.
 */
@Service
public class DebugRoundService {
    static final int MAX_TRIES = 40;  // candidate bugs tested per problem; most are caught by the first test
    private static final String PLANTER = resource("debug/plant_bugs.py");

    private final JdbcClient jdbc;
    private final ProblemService problems;
    private final JudgeService judge;
    private final AppProperties props;
    private final ObjectMapper json;

    public DebugRoundService(JdbcClient jdbc, ProblemService problems, JudgeService judge, AppProperties props, ObjectMapper json) {
        this.jdbc = jdbc;
        this.problems = problems;
        this.judge = judge;
        this.props = props;
        this.json = json;
    }

    /** One planted bug, 1-based line; "original" is the fixed line. */
    public record Bug(int line, String original, String buggy) {}

    record Plan(String normal, List<String> mutants) {}

    record Starter(String code, List<Bug> bugs) {}

    /** Worker side of a DEBUG job: plants bugs in every problem of the round that has no starter code yet. */
    public Map<String, Object> plant(JobQueue.Job job) throws Exception {
        long definitionId = json.readTree(job.payload()).get("definitionId").asLong();
        int count = jdbc.sql("select bugs_per_problem from oa_definitions where id = ?").param(definitionId).query(Integer.class).single();
        Random random = new Random(job.id());  // every round is a new job, so every round gets different bugs
        List<Long> pending = jdbc.sql("""
                select problem_id from oa_problems op where definition_id = ? and not exists
                       (select 1 from oa_starter_code s where s.definition_id = op.definition_id and s.problem_id = op.problem_id)
                 order by ordinal""").param(definitionId).query(Long.class).list();
        for (long problemId : pending) {
            JudgeInfo p = problems.find(problemId).orElseThrow();
            judge.ensureOfficialTests(p);
            Starter s = plant(p, judge.officialTests(problemId), count, random);
            jdbc.sql("insert into oa_starter_code (definition_id, problem_id, code, bugs) values (?, ?, ?, cast(? as jsonb)) on conflict do nothing")
                    .params(definitionId, problemId, s.code(), json.writeValueAsString(s.bugs())).update();
        }
        return Map.of("definitionId", definitionId);
    }

    Starter plant(JudgeInfo p, List<TestRow> tests, int count, Random random) throws Exception {
        List<TestRow> quickFirst = tests.stream().sorted(Comparator.comparingInt(t -> t.input().length())).toList();
        try (CodeRunner runner = CodeRunner.open(props.sandbox(), Language.PYTHON, p.memoryLimitMb())) {
            runner.compile(PLANTER);
            Sandbox.Exec listed = runner.run(p.referencePy(), 60, p.memoryLimitMb());
            if (listed.exitCode() != 0) throw new IllegalStateException("bug planter failed for problem " + p.id() + ": " + listed.stderr());
            Plan plan = json.readValue(listed.stdout(), Plan.class);
            String[] normal = plan.normal().split("\n", -1);
            if (caught(runner, plan.normal(), quickFirst.stream().filter(t -> "SAMPLE".equals(t.kind())).toList(), p)) {
                throw new IllegalStateException("the reference solution of problem " + p.id() + " fails its own samples");
            }
            List<String> mutants = new ArrayList<>(plan.mutants());
            Collections.shuffle(mutants, random);
            TreeMap<Integer, String> chosen = new TreeMap<>();  // line index -> buggy line
            int tries = 0;
            for (String mutant : mutants) {
                if (chosen.size() == count || tries == MAX_TRIES) break;
                String[] lines = mutant.split("\n", -1);
                int line = onlyChangedLine(normal, lines);
                if (line < 0 || chosen.containsKey(line)) continue;
                tries++;
                if (caught(runner, mutant, quickFirst, p)) chosen.put(line, lines[line]);
            }
            if (chosen.isEmpty()) throw new IllegalStateException("no planted bug in problem " + p.id() + " was caught by its tests");
            String code = withLines(normal, chosen);
            if (chosen.size() > 1 && !caught(runner, code, quickFirst, p)) {  // the bugs cancel out: keep the first alone
                chosen.keySet().retainAll(List.of(chosen.firstKey()));
                code = withLines(normal, chosen);
            }
            return new Starter(code, chosen.entrySet().stream().map(e -> new Bug(e.getKey() + 1, normal[e.getKey()], e.getValue())).toList());
        }
    }

    /** True when some test rejects the program, so the bug in it can be found through the tests. */
    private boolean caught(CodeRunner runner, String code, List<TestRow> tests, JudgeInfo p) {
        if (!runner.compile(code).ok()) return true;
        double tl = Language.PYTHON.timeLimitSeconds(p.timeLimitMs());
        int outputLimitKb = props.sandbox().outputLimitKb();
        for (TestRow t : tests) {
            Sandbox.Exec e = runner.run(t.input(), tl, p.memoryLimitMb());
            Verdict v = Verdicts.classify(e, tl, p.memoryLimitMb() * 1024L, Language.PYTHON, outputLimitKb, t.expectedOutput()).verdict();
            if (v != Verdict.ACCEPTED) return true;
        }
        return false;
    }

    /** The index of the one line that differs, or -1 when the line count changed or several lines differ. */
    static int onlyChangedLine(String[] a, String[] b) {
        if (a.length != b.length) return -1;
        int changed = -1;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(b[i])) continue;
            if (changed >= 0) return -1;
            changed = i;
        }
        return changed;
    }

    private static String withLines(String[] normal, Map<Integer, String> replaced) {
        String[] lines = normal.clone();
        replaced.forEach((i, line) -> lines[i] = line);
        return String.join("\n", lines);
    }

    private static String resource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
