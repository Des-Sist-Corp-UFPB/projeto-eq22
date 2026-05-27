create table chapters (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    section_id uuid not null references sections(id) on delete cascade,
    title varchar(255) not null,
    summary text,
    sort_order integer not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_chapters_section_sort_order on chapters(section_id, sort_order);
create index idx_chapters_book_id on chapters(book_id);
