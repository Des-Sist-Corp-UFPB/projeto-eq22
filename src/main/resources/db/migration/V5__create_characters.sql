create table characters (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    name varchar(255) not null,
    nickname varchar(255),
    age integer,
    sex varchar(80),
    narrative_function varchar(255),
    goal text,
    conflict text,
    arc text,
    physical_description text,
    personality text,
    biography text,
    notes text,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_characters_book_id on characters(book_id);
create index idx_characters_book_name on characters(book_id, name);
