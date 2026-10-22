package com.algoforge.problems;

import com.algoforge.common.ApiException;
import com.algoforge.common.Audit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Shared lookups: visibility, assessment locks and the limits the judge needs. */
@Service
public class ProblemService {
    private final JdbcClient jdbc;
    private final Audit audit;

    public ProblemService(JdbcClient jdbc, Audit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record JudgeInfo(long id, String title, String source, int timeLimitMs, int memoryLimitMb, Integer maxN,
                            int inputSizePower, String statementMd, String constraintsMd, String referencePy,
                            String generatorPy, String validatorPy, String generatedSpecs, int version) {}

    /** Shared catalogue problems plus the caller's own private problems. */
    public JudgeInfo visible(long userId, long problemId) {
        return find(problemId).filter(p -> isVisible(userId, problemId)).orElseThrow(() -> ApiException.notFound("Problem"));
    }

    public Optional<JudgeInfo> find(long problemId) {
        return jdbc.sql("""
                select id, title, source, time_limit_ms, memory_limit_mb, max_n, input_size_power, statement_md, constraints_md,
                       reference_py, generator_py, validator_py, generated_specs::text, version
                  from problems where id = ?""").param(problemId).query(JudgeInfo.class).optional();
    }

    public boolean isVisible(long userId, long problemId) {
        return jdbc.sql("select count(*) from problems where id = ? and (owner_id is null or owner_id = ?)")
                .params(problemId, userId).query(Long.class).single() > 0;
    }

    /** True while the problem is part of the user's running assessment that forbids hints (and the coach). */
    public boolean lockedByAssessment(long userId, long problemId) { return runningAssessment(userId, problemId, true); }

    /** True while the problem is part of the user's running assessment: solutions and hidden tests stay hidden, even when hints are allowed. */
    public boolean inRunningAssessment(long userId, long problemId) { return runningAssessment(userId, problemId, false); }

    private boolean runningAssessment(long userId, long problemId, boolean onlyWithoutHints) {
        return jdbc.sql("""
                select count(*) from oa_sessions s
                  join oa_definitions d on d.id = s.definition_id
                  join oa_problems op on op.definition_id = d.id
                 where s.user_id = :u and s.status <> 'COMPLETED' and d.mode = 'ASSESSMENT'
                   and (not :onlyWithoutHints or not d.allow_hints) and op.problem_id = :p""")
                .param("u", userId).param("p", problemId).param("onlyWithoutHints", onlyWithoutHints).query(Long.class).single() > 0;
    }

    /** The user saw a solution (editorial or a written outline): later accepted submissions count as weaker evidence. */
    public void markRevealed(long userId, long problemId) {
        jdbc.sql("""
                insert into user_problem_state (user_id, problem_id, revealed_at) values (?, ?, now())
                on conflict (user_id, problem_id) do update set revealed_at = coalesce(user_problem_state.revealed_at, now())""")
                .params(userId, problemId).update();
        audit.log(userId, "REVEAL_SOLUTION", Map.of("problemId", problemId), null);
    }

    /** A problem can be judged locally when it has stored tests or generator specs. */
    public boolean judgeable(long problemId) {
        return jdbc.sql("""
                select (exists (select 1 from test_cases where problem_id = p.id and kind in ('SAMPLE', 'OFFICIAL'))
                        or jsonb_array_length(p.generated_specs) > 0) from problems p where p.id = ?""")
                .param(problemId).query(Boolean.class).optional().orElse(false);
    }

    public List<String> conceptCodes(long problemId) {
        return jdbc.sql("select concept_code from problem_concepts where problem_id = ? order by concept_code")
                .param(problemId).query(String.class).list();
    }
}
