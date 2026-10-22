-- AlgoForge core schema.

-- Users and authentication -------------------------------------------------------------------
create table users (
    id            bigserial primary key,
    email         text not null check (length(email) between 3 and 254),
    display_name  text not null check (length(display_name) between 1 and 60),
    password_hash text not null,
    role          text not null default 'USER' check (role in ('USER', 'ADMIN')),
    created_at    timestamptz not null default now()
);
create unique index users_email_key on users (lower(email));

create table user_preferences (
    user_id            bigint primary key references users (id) on delete cascade,
    preferred_language text not null default 'python' check (preferred_language in ('java', 'cpp', 'python')),
    daily_minutes      int not null default 60 check (daily_minutes between 10 and 480),
    target_difficulty  text not null default 'MEDIUM' check (target_difficulty in ('EASY', 'MEDIUM', 'HARD')),
    goal_text          text check (length(goal_text) <= 200),
    goal_date          date,
    goal_concepts      text[] not null default '{}',
    show_streak        boolean not null default false,
    updated_at         timestamptz not null default now()
);

-- Problem catalogue ----------------------------------------------------------------------------
create table problem_sources (
    code              text primary key,
    name              text not null,
    homepage          text,
    last_sync_at      timestamptz,
    last_sync_status  text not null default 'NEVER' check (last_sync_status in ('NEVER', 'OK', 'FAILED')),
    last_sync_message text,
    last_sync_count   int
);

create table problems (
    id              bigserial primary key,
    source          text not null references problem_sources (code),
    external_id     text,
    canonical_url   text,
    slug            text unique,
    title           text not null check (length(title) between 1 and 200),
    statement_md    text,            -- only stored when we are permitted to (original problems)
    constraints_md  text,
    editorial_md    text,
    hints           jsonb not null default '[]',
    difficulty      text check (difficulty in ('EASY', 'MEDIUM', 'HARD')),
    source_rating   int,
    est_minutes     int,
    time_limit_ms   int not null default 2000 check (time_limit_ms between 100 and 10000),
    memory_limit_mb int not null default 256 check (memory_limit_mb between 16 and 1024),
    max_n           int,
    input_size_power int not null default 1,   -- input size grows as max_n^power (2 for an n x n grid)
    reference_py    text,            -- trusted oracle, never sent to clients
    generator_py    text,            -- python gen.py <seed> <n>
    validator_py    text,            -- exits non-zero on invalid input
    generated_specs jsonb not null default '[]',  -- [{seed, n}] official tests the worker materialises
    owner_id        bigint references users (id) on delete cascade,  -- null = shared catalogue
    version         int not null default 1,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);
create unique index problems_external_key on problems (source, external_id, coalesce(owner_id, 0)) where external_id is not null;
create unique index problems_url_key on problems (canonical_url, coalesce(owner_id, 0)) where canonical_url is not null;
create index problems_difficulty_idx on problems (difficulty);

create table problem_tags (
    problem_id bigint not null references problems (id) on delete cascade,
    tag        text not null check (length(tag) between 1 and 60),
    primary key (problem_id, tag)
);
create index problem_tags_tag_idx on problem_tags (tag);

create table concepts (
    code        text primary key,
    name        text not null,
    description text not null,
    sort_order  int not null
);

create table concept_prerequisites (
    concept_code text not null references concepts (code),
    requires     text not null references concepts (code),
    primary key (concept_code, requires),
    check (concept_code <> requires)
);

create table problem_concepts (
    problem_id   bigint not null references problems (id) on delete cascade,
    concept_code text not null references concepts (code),
    tier         smallint not null check (tier between 1 and 3),  -- 1 direct, 2 variant, 3 disguised
    primary key (problem_id, concept_code)
);
create index problem_concepts_concept_idx on problem_concepts (concept_code, tier);

create table user_problem_state (
    user_id             bigint not null references users (id) on delete cascade,
    problem_id          bigint not null references problems (id) on delete cascade,
    status              text not null default 'UNSEEN' check (status in ('UNSEEN', 'ATTEMPTED', 'SOLVED', 'NEEDS_REVISION')),
    notes               text check (length(notes) <= 5000),
    personal_difficulty smallint check (personal_difficulty between 1 and 5),
    solved_at           timestamptz,
    revealed_at         timestamptz,   -- editorial or reference solution viewed (discounts learning evidence)
    updated_at          timestamptz not null default now(),
    primary key (user_id, problem_id)
);

create table test_cases (
    id                bigserial primary key,
    problem_id        bigint not null references problems (id) on delete cascade,
    kind              text not null check (kind in ('SAMPLE', 'OFFICIAL', 'GENERATED')),
    ordinal           int not null,
    input             text not null,
    expected_output   text,
    verification      text not null check (verification in ('AUTHOR', 'REFERENCE', 'UNVERIFIED')),
    provenance        text not null,
    generator_version text,
    seed              bigint,
    category          text,
    owner_id          bigint references users (id) on delete cascade,  -- GENERATED tests belong to one user
    created_at        timestamptz not null default now()
);
create index test_cases_problem_idx on test_cases (problem_id, kind, ordinal);

-- Online assessments --------------------------------------------------------------------------
create table oa_definitions (
    id                bigserial primary key,
    owner_id          bigint references users (id) on delete cascade,  -- null = built-in template
    title             text not null check (length(title) between 1 and 120),
    description       text,
    is_template       boolean not null default false,
    recipe            jsonb,  -- templates: {"slots": [{"difficulty": "EASY", "points": 100}]}
    duration_minutes  int not null check (duration_minutes between 5 and 300),
    mode              text not null default 'ASSESSMENT' check (mode in ('ASSESSMENT', 'PRACTICE')),
    partial_credit    boolean not null default true,
    wrong_penalty_pct int not null default 0 check (wrong_penalty_pct between 0 and 50),
    languages         text[] not null default '{java,cpp,python}',
    allow_hints       boolean not null default false,
    created_at        timestamptz not null default now()
);

create table oa_problems (
    definition_id bigint not null references oa_definitions (id) on delete cascade,
    problem_id    bigint not null references problems (id),
    ordinal       int not null,
    points        int not null check (points between 1 and 1000),
    primary key (definition_id, problem_id)
);

create table oa_sessions (
    id            bigserial primary key,
    definition_id bigint not null references oa_definitions (id),
    user_id       bigint not null references users (id) on delete cascade,
    status        text not null default 'ACTIVE' check (status in ('ACTIVE', 'FINALIZING', 'COMPLETED')),
    started_at    timestamptz not null default now(),
    ends_at       timestamptz not null,
    ended_at      timestamptz,
    end_reason    text check (end_reason in ('SUBMITTED', 'EXPIRED')),
    finalized_at  timestamptz,
    total_score   numeric(8, 2),
    max_score     int,
    report        jsonb
);
create index oa_sessions_user_idx on oa_sessions (user_id, started_at desc);
create index oa_sessions_open_idx on oa_sessions (ends_at) where status <> 'COMPLETED';
create unique index oa_sessions_one_active on oa_sessions (user_id) where status = 'ACTIVE';

create table oa_events (
    id         bigserial primary key,
    session_id bigint not null references oa_sessions (id) on delete cascade,
    problem_id bigint not null references problems (id),
    at         timestamptz not null default now()
);
create index oa_events_session_idx on oa_events (session_id, at);

-- Submissions and execution ------------------------------------------------------------------
create table submissions (
    id                bigserial primary key,
    user_id           bigint not null references users (id) on delete cascade,
    problem_id        bigint not null references problems (id) on delete cascade,
    oa_session_id     bigint references oa_sessions (id) on delete cascade,
    language          text not null check (language in ('java', 'cpp', 'python')),
    code              text not null check (length(code) between 1 and 65536),
    stated_complexity text check (length(stated_complexity) <= 40),
    status            text not null default 'QUEUED' check (status in ('QUEUED', 'COMPILING', 'RUNNING', 'ACCEPTED',
                          'WRONG_ANSWER', 'RUNTIME_ERROR', 'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED',
                          'COMPILATION_ERROR', 'INTERNAL_ERROR')),
    passed            int,
    total             int,
    max_time_ms       int,
    max_memory_kb     int,
    compile_output    text,
    message           text,
    runtime_version   text,
    created_at        timestamptz not null default now(),
    judged_at         timestamptz
);
create index submissions_user_problem_idx on submissions (user_id, problem_id, created_at desc);
create index submissions_user_created_idx on submissions (user_id, created_at desc);
create index submissions_session_idx on submissions (oa_session_id) where oa_session_id is not null;

create table submission_results (
    submission_id  bigint not null references submissions (id) on delete cascade,
    ordinal        int not null,
    test_case_id   bigint references test_cases (id) on delete set null,
    kind           text not null,
    verdict        text not null,
    time_ms        int,
    memory_kb      int,
    output_excerpt text,
    error_excerpt  text,
    primary key (submission_id, ordinal)
);

create table execution_jobs (
    id            bigserial primary key,
    type          text not null check (type in ('JUDGE', 'RUN', 'EVALUATE')),
    user_id       bigint references users (id) on delete cascade,
    submission_id bigint references submissions (id) on delete cascade,
    payload       jsonb not null default '{}',
    status        text not null default 'QUEUED' check (status in ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    attempts      int not null default 0,
    result        jsonb,
    error         text,
    worker_id     text,
    locked_at     timestamptz,
    created_at    timestamptz not null default now(),
    finished_at   timestamptz
);
create index execution_jobs_queue_idx on execution_jobs (id) where status = 'QUEUED';
create index execution_jobs_running_idx on execution_jobs (locked_at) where status = 'RUNNING';

-- Evaluation -----------------------------------------------------------------------------------
create table evaluation_reports (
    id               bigserial primary key,
    submission_id    bigint not null unique references submissions (id) on delete cascade,
    status           text not null default 'BASIC' check (status in ('BASIC', 'DEEP_PENDING', 'DEEP_READY', 'DEEP_FAILED')),
    correctness      text not null check (correctness in ('VERIFIED', 'PROVISIONAL', 'FAILED')),
    overall_score    numeric(5, 1) not null,
    dimensions       jsonb not null,
    findings         jsonb not null default '[]',
    generated_tests  jsonb,
    benchmark        jsonb,
    ai_review        jsonb,
    ai_disagreements jsonb,
    ai_status        text,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

create table complexity_assessments (
    id               bigserial primary key,
    submission_id    bigint not null references submissions (id) on delete cascade,
    source           text not null,  -- static | empirical | ai:claude | ai:gemini
    time_complexity  text,
    space_complexity text,
    explanation      text,
    confidence       text not null check (confidence in ('LOW', 'MEDIUM', 'HIGH')),
    details          jsonb,
    created_at       timestamptz not null default now(),
    unique (submission_id, source)
);

-- Learning -------------------------------------------------------------------------------------
create table learning_events (
    id            bigserial primary key,
    user_id       bigint not null references users (id) on delete cascade,
    concept_code  text not null references concepts (code),
    dimension     text not null check (dimension in ('RECOGNITION', 'IMPLEMENTATION', 'EXPLANATION', 'COMPLEXITY',
                      'TRANSFER', 'TIME_PRESSURE', 'RETENTION')),
    success       boolean not null,
    weight        real not null default 1,
    context       text not null check (context in ('PRACTICE', 'ASSESSMENT')),
    problem_id    bigint references problems (id) on delete set null,
    submission_id bigint references submissions (id) on delete set null,
    created_at    timestamptz not null default now()
);
create index learning_events_user_idx on learning_events (user_id, concept_code, created_at);

create table concept_confidence (
    user_id          bigint not null references users (id) on delete cascade,
    concept_code     text not null references concepts (code),
    dimension        text not null,
    successes        real not null default 0,
    failures         real not null default 0,
    evidence_count   int not null default 0,
    last_evidence_at timestamptz,
    last_success_at  timestamptz,
    primary key (user_id, concept_code, dimension)
);

create table recommendations (
    id           bigserial primary key,
    user_id      bigint not null references users (id) on delete cascade,
    problem_id   bigint not null references problems (id) on delete cascade,
    concept_code text references concepts (code),
    kind         text not null check (kind in ('TRANSFER', 'WEAKNESS', 'REVISION', 'GOAL', 'NEW')),
    reason       text not null,
    status       text not null default 'PENDING' check (status in ('PENDING', 'ACCEPTED', 'SKIPPED', 'REPLACED', 'COMPLETED')),
    for_date     date not null default current_date,
    created_at   timestamptz not null default now(),
    acted_at     timestamptz
);
create index recommendations_user_idx on recommendations (user_id, for_date, status);

-- AI providers ---------------------------------------------------------------------------------
create table ai_provider_configs (
    provider          text primary key check (provider in ('claude', 'gemini')),
    enabled           boolean not null default true,
    model             text not null,
    roles             text[] not null,  -- REVIEW, TESTGEN
    max_output_tokens int not null default 16000,
    updated_at        timestamptz not null default now()
);

create table ai_cache (
    cache_key  text primary key,
    provider   text not null,
    model      text not null,
    task       text not null,
    response   jsonb not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null
);

create table ai_usage (
    id            bigserial primary key,
    user_id       bigint references users (id) on delete set null,
    provider      text not null,
    model         text not null,
    task          text not null,
    status        text not null check (status in ('OK', 'CACHED', 'ERROR', 'TIMEOUT', 'INVALID_OUTPUT', 'RATE_LIMITED')),
    input_tokens  int,
    output_tokens int,
    latency_ms    int,
    error         text,
    created_at    timestamptz not null default now()
);
create index ai_usage_user_idx on ai_usage (user_id, created_at);

create table audit_log (
    id         bigserial primary key,
    user_id    bigint references users (id) on delete set null,
    action     text not null,
    detail     jsonb,
    ip         text,
    created_at timestamptz not null default now()
);
create index audit_log_created_idx on audit_log (created_at);
