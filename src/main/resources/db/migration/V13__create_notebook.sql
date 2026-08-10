create table notebook_categories (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    name varchar(255) not null,
    sort_order integer not null,
    is_default boolean not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table notebook_notes (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    category_id uuid references notebook_categories(id) on delete set null,
    title varchar(255) not null,
    content text,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_notebook_categories_book_order on notebook_categories(book_id, sort_order, name);
create unique index uk_notebook_categories_book_name_ci on notebook_categories(book_id, lower(name));

create index idx_notebook_notes_book_updated on notebook_notes(book_id, updated_at desc);
create index idx_notebook_notes_category_id on notebook_notes(category_id);
