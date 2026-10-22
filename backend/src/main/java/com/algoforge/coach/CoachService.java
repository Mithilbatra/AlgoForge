package com.algoforge.coach;

import com.algoforge.ai.AiOutputs;
import com.algoforge.ai.AiService;
import com.algoforge.common.ApiException;
import com.algoforge.eval.StaticAnalyzer;
import com.algoforge.judge.Language;
import com.algoforge.problems.ProblemService;
import com.algoforge.problems.ProblemService.JudgeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Forge Coach: hints at three levels (nudge, approach, outline) grounded in the statement, the learner's current code,
 * their last judged result and the static analysis. Uses the configured AI provider when there is one and the
 * built-in hint ladder otherwise. Never available during an assessment; an outline counts as a solution reveal.
 */
@Service
@Profile("!worker")
public class CoachService {
    static final String[] LEVEL = {"", "Nudge", "Approach", "Outline"};

    private final JdbcClient jdbc;
    private final ProblemService problems;
    private final AiService ai;
    private final ObjectMapper json;

    public CoachService(JdbcClient jdbc, ProblemService problems, AiService ai, ObjectMapper json) {
        this.jdbc = jdbc;
        this.problems = problems;
        this.ai = ai;
        this.json = json;
    }

    public record Turn(@Pattern(regexp = "user|coach") String role, @Size(max = 2000) String text) {}

    public record Request(@Min(1) @Max(3) int level, @Size(max = 1000) String message, @Size(max = 65536) String code,
                          @Pattern(regexp = "java|cpp|python") String language, @Size(max = 8) List<@Valid Turn> history,
                          Long submissionId) {}

    /** source is "ai" or "built-in"; notice explains fallbacks and reveal accounting. */
    public record Reply(String reply, String nextStep, int level, String source, String provider, String model, String notice) {}

    record Failure(String status, String message, String kind, String input, String expected, String output) {}

    public Reply coach(long userId, long problemId, Request r) {
        JudgeInfo p = problems.visible(userId, problemId);
        if (problems.lockedByAssessment(userId, problemId)) throw ApiException.forbidden("Forge Coach is off while this problem is part of a running assessment");
        Optional<Failure> failure = lastResult(userId, problemId, r.submissionId());
        StaticAnalyzer.Result analysis = r.code() == null || r.code().isBlank() || r.language() == null ? null
                : StaticAnalyzer.analyze(r.code(), Language.of(r.language()), p.constraintsMd());
        String revealNotice = null;
        if (r.level() == 3) {
            // An outline is close to a solution, so a later accepted submission counts as weaker evidence.
            jdbc.sql("""
                    insert into user_problem_state (user_id, problem_id, revealed_at) values (?, ?, now())
                    on conflict (user_id, problem_id) do update set revealed_at = coalesce(user_problem_state.revealed_at, now())""")
                    .params(userId, problemId).update();
            revealNotice = "Outlines count as a solution reveal: a later accepted submission weighs less as evidence.";
        }
        String fallbackNotice = null;
        if (ai.anyAvailable("REVIEW")) {
            var call = ai.coach(userId, prompt(p, r, failure, analysis)).orElseThrow();
            if (call.ok()) {
                return new Reply(call.value().reply(), call.value().nextStep(), r.level(), "ai", call.provider(), call.model(), revealNotice);
            }
            fallbackNotice = "The AI coach is unavailable right now (" + call.status().toLowerCase().replace('_', ' ') + "), so the built-in coach answered.";
        } else if (r.message() != null && !r.message().isBlank()) {
            fallbackNotice = "Free-form questions need an AI provider. Here is the built-in coach's take instead.";
        }
        return builtIn(p, r.level(), failure, analysis, join(fallbackNotice, revealNotice));
    }

    // ---- built-in coach ------------------------------------------------------------------------------------------

    Reply builtIn(JudgeInfo p, int level, Optional<Failure> failure, StaticAnalyzer.Result a, String notice) {
        List<String> parts = new ArrayList<>();
        failure.filter(f -> !"ACCEPTED".equals(f.status())).map(f -> diagnose(f, a, p)).ifPresent(parts::add);
        if (a != null) {
            a.findings().stream().filter(f -> "WARN".equals(f.severity())).limit(2).forEach(f -> parts.add("Also noticed: " + f.message()));
        }
        List<String> hints = hints(p);
        if (!hints.isEmpty()) {
            int i = Math.min(level, hints.size()) - 1;
            parts.add("**Hint " + (i + 1) + " of " + hints.size() + ".** " + hints.get(i));
        } else {
            parts.add(switch (level) {
                case 1 -> "Start from the constraints: the largest input tells you which running times are possible. Which structure in the input could you exploit?";
                case 2 -> "Write down the brute force first, then ask which repeated work it does and what could remember or skip it.";
                default -> "Break the problem into: read input, the one core computation, and output. Make the core computation fit the constraints before polishing anything else.";
            });
        }
        if (failure.map(Failure::status).filter("ACCEPTED"::equals).isPresent()) {
            parts.addFirst("Your last submission was accepted. Nice forging. Look for a transfer test on the same idea next.");
        }
        String next = switch (level) {
            case 1 -> "Try the idea on the first sample by hand. Ask for the approach if you're still stuck.";
            case 2 -> "Estimate the running time of that approach at the maximum input before coding it.";
            default -> "Implement one step at a time and run the samples after each step.";
        };
        return new Reply(String.join("\n\n", parts), next, level, "built-in", null, null, notice);
    }

    static String diagnose(Failure f, StaticAnalyzer.Result a, JudgeInfo p) {
        return switch (f.status()) {
            case "WRONG_ANSWER" -> "SAMPLE".equals(f.kind())
                    ? "Your last submission fails a visible sample. Trace that sample by hand, line by line, and compare each value with what you expect."
                    : "The samples pass, but a hidden test fails. Think about cases the samples skip: the smallest input, equal values, negatives, and values large enough to overflow 32-bit integers.";
            case "TIME_LIMIT_EXCEEDED" -> "It's too slow." + (a != null ? " Your code looks like " + a.timeComplexity() + "." : "")
                    + " With constraints " + (p.constraintsMd() == null ? "like these" : p.constraintsMd())
                    + ", budget roughly 10^8 simple operations per second. Find the loop that repeats work.";
            case "MEMORY_LIMIT_EXCEEDED" -> "It runs out of memory. Check whether you store more than you need: often a rolling array or a running total replaces a big table.";
            case "RUNTIME_ERROR" -> "It crashes" + (f.message() == null ? "." : " (" + f.message() + ").")
                    + " Check indices at the array boundaries, division by zero, and recursion depth on long inputs.";
            case "COMPILATION_ERROR" -> "It doesn't compile. The first compiler error is usually the real one; the rest often follow from it.";
            default -> "Your last submission ended with " + f.status().toLowerCase().replace('_', ' ') + ".";
        };
    }

    // ---- context ---------------------------------------------------------------------------------------------------

    Optional<Failure> lastResult(long userId, long problemId, Long submissionId) {
        return jdbc.sql("""
                select s.status, s.message,
                       r.kind, left(t.input, 600) as input, left(t.expected_output, 300) as expected, left(r.output_excerpt, 300) as output
                  from submissions s
                  left join lateral (select * from submission_results x where x.submission_id = s.id and x.verdict <> 'ACCEPTED'
                                      order by x.ordinal limit 1) r on true
                  left join test_cases t on t.id = r.test_case_id
                 where s.user_id = :u and s.problem_id = :p and s.judged_at is not null
                   and (cast(:sid as bigint) is null or s.id = :sid)
                 order by s.id desc limit 1""")
                .param("u", userId).param("p", problemId).param("sid", submissionId)
                .query(Failure.class).optional();
    }

    List<String> hints(JudgeInfo p) {
        String raw = jdbc.sql("select hints::text from problems where id = ?").param(p.id()).query(String.class).single();
        List<String> out = new ArrayList<>();
        try {
            json.readTree(raw).forEach(h -> out.add(h.asText()));
        } catch (Exception e) {
            throw new IllegalStateException("bad hints json for problem " + p.id(), e);
        }
        return out;
    }

    private String prompt(JudgeInfo p, Request r, Optional<Failure> failure, StaticAnalyzer.Result a) {
        StringBuilder sb = new StringBuilder("## Requested hint level: ").append(r.level()).append(" (").append(LEVEL[r.level()]).append(")\n\n")
                .append("## Problem: ").append(p.title()).append("\n").append(cap(p.statementMd(), 8000))
                .append("\n\n## Constraints\n").append(p.constraintsMd() == null ? "" : p.constraintsMd()).append("\n");
        List<String> hints = hints(p);
        if (!hints.isEmpty()) {
            sb.append("\n## Reference hints (for calibration; reveal no more than the requested level)\n");
            hints.forEach(h -> sb.append("- ").append(h).append("\n"));
        }
        if (r.code() != null && !r.code().isBlank()) {
            sb.append("\n## Learner's current code (").append(r.language()).append(")\n```\n").append(cap(r.code(), 20000)).append("\n```\n");
        }
        if (a != null) {
            sb.append("\n## Static analysis (heuristic): time ").append(a.timeComplexity()).append(", space ").append(a.spaceComplexity()).append("\n");
            a.findings().forEach(f -> sb.append("- ").append(f.message()).append("\n"));
        }
        failure.ifPresent(f -> {
            sb.append("\n## Last judged submission: ").append(f.status());
            if (f.message() != null) sb.append(" (").append(f.message()).append(")");
            sb.append("\n");
            if (f.input() != null) {
                sb.append("First failing test (").append(f.kind()).append(") input:\n```\n").append(f.input())
                        .append("\n```\nexpected: ").append(f.expected()).append("\ngot: ").append(f.output()).append("\n");
            }
        });
        if (r.history() != null && !r.history().isEmpty()) {
            sb.append("\n## Conversation so far\n");
            r.history().forEach(t -> sb.append(t.role()).append(": ").append(cap(t.text(), 2000)).append("\n"));
        }
        sb.append("\n## Learner's message\n").append(r.message() == null || r.message().isBlank() ? "(Give me a hint at this level.)" : r.message());
        return sb.toString();
    }

    private static String cap(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "\n…"; }

    private static String join(String a, String b) { return a == null ? b : b == null ? a : a + " " + b; }
}
