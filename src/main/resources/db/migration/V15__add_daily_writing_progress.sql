alter table books
    add column daily_target_word_count integer;

create table book_daily_writing_progress (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    progress_date date not null,
    daily_target_word_count integer,
    start_word_count integer not null,
    end_word_count integer not null,
    net_word_count_change integer not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_book_daily_writing_progress_book_date unique (book_id, progress_date)
);

create index idx_book_daily_writing_progress_book_date
    on book_daily_writing_progress(book_id, progress_date desc);
