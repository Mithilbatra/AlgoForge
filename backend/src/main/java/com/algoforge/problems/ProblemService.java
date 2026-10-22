package com.algoforge.problems;

import com.algoforge.common.ApiException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Shared lookups: visibility, assessment locks and the limits the judge needs. */
@Service
public class ProblemService {
    private final JdbcClient jdbc;

    public ProblemService(JdbcClient jdbc) { this.jdbc = jdbc; }

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

    /** True while the problem is part of the user's running assessment that forbids hints and solutions. */
    public boolean lockedByAssessment(long userId, long problemId) {
        return jdbc.sql("""
                select count(*) from oa_sessions s
                  join oa_definitions d on d.id = s.definition_id
                  join oa_problems op on op.definition_id = d.id
                 where s.user_id = ? and s.status <> 'COMPLETED' and d.mode = 'ASSESSMENT'
                   and not d.allow_hints and op.problem_id = ?""")
                .params(userId, problemId).query(Long.class).single() > 0;
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
