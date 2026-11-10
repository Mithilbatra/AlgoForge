-- Debugging rounds: an assessment whose programs start as a problem's reference solution with bugs planted in it.
-- Every new round plants new bugs (a DEBUG job), each one caught by the problem's tests and fixable back to the reference.
alter table oa_definitions
    add column bugs_per_problem smallint check (bugs_per_problem between 1 and 5),  -- null for a normal round
    add column starter_job_id bigint references execution_jobs (id) on delete set null;

create table oa_starter_code (
    definition_id bigint not null references oa_definitions (id) on delete cascade,
    problem_id    bigint not null references problems (id) on delete cascade,
    code          text not null,
    bugs          jsonb not null,  -- [{"line": 7, "original": "...", "buggy": "..."}]: the answer key, shown after the round
    primary key (definition_id, problem_id)
);

alter table execution_jobs drop constraint execution_jobs_type_check;
alter table execution_jobs add constraint execution_jobs_type_check check (type in ('JUDGE', 'RUN', 'EVALUATE', 'TWIN', 'DEBUG'));

insert into oa_definitions (title, description, is_template, recipe, duration_minutes, mode, partial_credit, wrong_penalty_pct,
                            allow_hints, languages, bugs_per_problem) values
    ('Debugging round', 'Three working Python programs, each with two bugs planted in it. Find and fix them so every test passes. Every new round plants different bugs.',
        true, '{"slots": [{"difficulty": "EASY", "points": 100}, {"difficulty": "MEDIUM", "points": 100}, {"difficulty": "MEDIUM", "points": 100}]}',
        30, 'ASSESSMENT', true, 0, false, '{python}', 2);
