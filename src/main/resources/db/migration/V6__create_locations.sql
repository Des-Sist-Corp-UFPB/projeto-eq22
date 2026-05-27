create table locations (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    name varchar(255) not null,
    type varchar(255),
    description text,
    history_context text,
    narrative_importance text,
    notes text,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_locations_book_id on locations(book_id);
create index idx_locations_book_name on locations(book_id, name);
