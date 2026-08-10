create table sections (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    title varchar(255) not null,
    type varchar(40) not null,
    sort_order integer not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_sections_book_sort_order on sections(book_id, sort_order);
