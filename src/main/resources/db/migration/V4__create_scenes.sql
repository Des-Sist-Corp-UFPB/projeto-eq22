create table scenes (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    chapter_id uuid not null references chapters(id) on delete cascade,
    title varchar(255) not null,
    summary text,
    content_json text,
    content_text text,
    status varchar(40) not null,
    sort_order integer not null,
    word_count integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_scenes_chapter_sort_order on scenes(chapter_id, sort_order);
create index idx_scenes_book_id on scenes(book_id);
