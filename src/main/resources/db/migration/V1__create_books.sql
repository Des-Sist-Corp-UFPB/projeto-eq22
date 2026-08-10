create table books (
    id uuid primary key,
    title varchar(255) not null,
    subtitle varchar(255),
    description text,
    status varchar(40) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
