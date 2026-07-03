create table book_collaboration_invitations (
    id uuid constraint pk_book_collaboration_invitations primary key,
    tenant_id uuid not null,
    book_id uuid not null,
    inviter_user_id uuid not null,
    recipient_email varchar(320) not null,
    requested_role varchar(32) not null,
    token_hash varchar(64) not null,
    status varchar(16) not null,
    expires_at timestamptz not null,
    accepted_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null,
    constraint fk_book_collaboration_invitations_book
        foreign key (tenant_id, book_id)
        references books (tenant_id, id)
        on delete cascade,
    constraint fk_book_collaboration_invitations_inviter_membership
        foreign key (tenant_id, inviter_user_id)
        references tenant_memberships (tenant_id, user_id),
    constraint chk_book_collaboration_invitations_status check (
        status in ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')
    ),
    constraint chk_book_collaboration_invitations_role check (
        requested_role in ('COLLABORATOR')
    ),
    constraint chk_book_collaboration_invitations_email_normalized check (
        recipient_email = lower(btrim(recipient_email))
        and recipient_email like '%_@_%'
    ),
    constraint chk_book_collaboration_invitations_token_hash check (
        token_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint chk_book_collaboration_invitations_lifecycle check (
        (status in ('PENDING', 'EXPIRED') and accepted_at is null and revoked_at is null)
        or (status = 'ACCEPTED' and accepted_at is not null and revoked_at is null)
        or (status = 'REVOKED' and revoked_at is not null and accepted_at is null)
    ),
    constraint uk_book_collaboration_invitations_token_hash unique (token_hash)
);

-- One usable invitation per (tenant, book, normalized email, role). A partial
-- unique index is enforced atomically by PostgreSQL under concurrent inserts:
-- the second insert blocks on the first and fails once it commits. Expired
-- rows are moved to EXPIRED before a replacement insert so they release the slot.
create unique index uk_book_collaboration_invitations_active
    on book_collaboration_invitations (tenant_id, book_id, recipient_email, requested_role)
    where status = 'PENDING';

create index idx_book_collaboration_invitations_tenant_book
    on book_collaboration_invitations (tenant_id, book_id, created_at desc);

create index idx_book_collaboration_invitations_pending_expires_at
    on book_collaboration_invitations (expires_at)
    where status = 'PENDING';
