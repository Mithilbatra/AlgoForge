-- Recording evidence first checks whether a submission was already recorded; without this index every judged
-- submission scanned the whole table.
create index learning_events_submission_idx on learning_events (submission_id);
