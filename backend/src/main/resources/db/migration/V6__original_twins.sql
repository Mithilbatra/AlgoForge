-- Original twins: a private problem the AI writes to test the same technique as a linked problem, in its own words.
-- Its AI-written reference solution, brute force and generator run only in the sandbox and are cross-checked before
-- the problem is saved.
alter table problems add column twin_of bigint references problems (id) on delete set null;
create unique index problems_twin_key on problems (twin_of, owner_id) where twin_of is not null;

alter table execution_jobs drop constraint execution_jobs_type_check;
alter table execution_jobs add constraint execution_jobs_type_check check (type in ('JUDGE', 'RUN', 'EVALUATE', 'TWIN'));
