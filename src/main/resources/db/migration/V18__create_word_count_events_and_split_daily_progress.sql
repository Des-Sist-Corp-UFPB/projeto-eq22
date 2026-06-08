alter table book_daily_writing_progress
    rename column start_word_count to starting_manuscript_word_count;

alter table book_daily_writing_progress
    rename column end_word_count to ending_manuscript_word_count;

alter table book_daily_writing_progress
    rename column net_word_count_change to productive_word_count_change;

alter table book_daily_writing_progress
    add column manuscript_adjustment_word_count integer not null default 0;

comment on column book_daily_writing_progress.productive_word_count_change is
    'Net authored manuscript growth from real content edits. This is not gross typing effort.';

comment on column book_daily_writing_progress.manuscript_adjustment_word_count is
    'Non-authoring manuscript word-count adjustments such as restore, delete, recovery, and import.';

create table book_word_count_events (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    scene_id uuid references scenes(id) on delete set null,
    original_scene_id uuid,
    scene_title_snapshot varchar(255),
    event_type varchar(40) not null,
    productive_word_delta integer not null,
    manuscript_word_delta integer not null,
    operation_id uuid,
    idempotency_key uuid not null,
    content_revision_before bigint,
    content_revision_after bigint,
    created_at timestamptz not null
);

create unique index uk_book_word_count_events_book_idempotency
    on book_word_count_events(book_id, idempotency_key);

create index idx_book_word_count_events_book_operation
    on book_word_count_events(book_id, operation_id);

create index idx_book_word_count_events_book_created_at
    on book_word_count_events(book_id, created_at desc);

create index idx_book_word_count_events_scene_created_at
    on book_word_count_events(scene_id, created_at desc);

create index idx_book_word_count_events_book_original_scene_created_at
    on book_word_count_events(book_id, original_scene_id, created_at desc);

comment on column book_word_count_events.productive_word_delta is
    'Net authored manuscript growth from real content edits. This is not gross typing effort.';

comment on column book_word_count_events.manuscript_word_delta is
    'Current manuscript size delta, including non-authoring adjustments.';

comment on column book_word_count_events.operation_id is
    'Groups events belonging to one logical user operation.';

comment on column book_word_count_events.idempotency_key is
    'Uniquely identifies one event for retry-safe insertion.';
