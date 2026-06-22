create table tenants (
    id uuid constraint pk_tenants primary key,
    name varchar(255) not null,
    default_time_zone_id varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table users (
    id uuid constraint pk_users primary key,
    display_name varchar(255) not null,
    email varchar(255) not null,
    time_zone_id varchar(64) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_users_email unique (email)
);

create table tenant_memberships (
    id uuid constraint pk_tenant_memberships primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    role varchar(32) not null,
    joined_at timestamptz not null,
    constraint fk_tenant_memberships_tenant foreign key (tenant_id) references tenants(id) on delete cascade,
    constraint fk_tenant_memberships_user foreign key (user_id) references users(id) on delete cascade,
    constraint uk_tenant_memberships_tenant_user unique (tenant_id, user_id),
    constraint chk_tenant_memberships_role check (role = 'OWNER')
);

-- Deterministic records preserve access to pre-tenant data during the personal-tenant transition.
-- Tenant: 00000000-0000-0000-0000-000000000001; user: 00000000-0000-0000-0000-000000000002.
insert into tenants (id, name, default_time_zone_id, created_at, updated_at)
values (
    '00000000-0000-0000-0000-000000000001',
    'Personal',
    'America/Sao_Paulo',
    timestamptz '2026-01-01 00:00:00+00',
    timestamptz '2026-01-01 00:00:00+00'
);

insert into users (id, display_name, email, time_zone_id, created_at, updated_at)
values (
    '00000000-0000-0000-0000-000000000002',
    'Carlos',
    'carlos.legacy@iwrite.local',
    'America/Sao_Paulo',
    timestamptz '2026-01-01 00:00:00+00',
    timestamptz '2026-01-01 00:00:00+00'
);

-- Membership: 00000000-0000-0000-0000-000000000003.
insert into tenant_memberships (id, tenant_id, user_id, role, joined_at)
values (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002',
    'OWNER',
    timestamptz '2026-01-01 00:00:00+00'
);

-- Nullable-first permits a safe backfill of every existing manuscript before enforcing ownership annotation.
alter table books add column tenant_id uuid;

update books
set tenant_id = '00000000-0000-0000-0000-000000000001'
where tenant_id is null;

alter table books alter column tenant_id set not null;

alter table books
    add constraint fk_books_tenant foreign key (tenant_id) references tenants(id);
