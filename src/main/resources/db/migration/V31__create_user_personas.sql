-- Declarative persona foundation for public registration (#143). A persona personalizes the
-- product; it never grants access to a tenant, book, or operation — no authorization check may
-- ever consult this table.
create table user_personas (
    id uuid constraint pk_user_personas primary key,
    user_id uuid not null,
    persona varchar(32) not null,
    is_primary boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint fk_user_personas_user foreign key (user_id) references users(id) on delete cascade,
    constraint uk_user_personas_user_persona unique (user_id, persona),
    constraint chk_user_personas_persona check (persona in ('WRITER', 'EDITOR', 'REVIEWER', 'BETA_READER', 'OTHER'))
);

-- At most one primary persona per user, enforced at the database level rather than only in
-- application code: a partial unique index over rows where is_primary is true.
create unique index uk_user_personas_primary_per_user on user_personas (user_id) where is_primary;

-- Backfill by email, not by the fixed legacy id: safe even if this migration ever runs against an
-- installation where the legacy user was never seeded.
insert into user_personas (id, user_id, persona, is_primary, created_at, updated_at)
select gen_random_uuid(), u.id, 'WRITER', true, now(), now()
from users u
where u.email = 'carlos.legacy@iwrite.local'
on conflict (user_id, persona) do nothing;
