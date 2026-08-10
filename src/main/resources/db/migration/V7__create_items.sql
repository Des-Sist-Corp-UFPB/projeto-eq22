create table items (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    name varchar(255) not null,
    type varchar(255),
    description text,
    origin text,
    current_owner_character_id uuid references characters(id) on delete set null,
    narrative_importance text,
    notes text,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_items_book_id on items(book_id);
create index idx_items_book_name on items(book_id, name);
create index idx_items_current_owner_character_id on items(current_owner_character_id);
