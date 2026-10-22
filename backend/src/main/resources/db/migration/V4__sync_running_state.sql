-- Catalogue syncs run in the background; RUNNING marks one in progress.
alter table problem_sources drop constraint problem_sources_last_sync_status_check;
alter table problem_sources add constraint problem_sources_last_sync_status_check
    check (last_sync_status in ('NEVER', 'RUNNING', 'OK', 'FAILED'));
