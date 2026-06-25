alter table books
    add column owner_user_id uuid;

update books book
set owner_user_id = (
    select membership.user_id
    from tenant_memberships membership
    where membership.tenant_id = book.tenant_id
    order by membership.joined_at asc, membership.user_id asc
    limit 1
)
where book.owner_user_id is null;

do $$
begin
    if exists (select 1 from books where owner_user_id is null) then
        raise exception 'Cannot add book ownership: at least one book has no tenant member owner fallback';
    end if;
end $$;

alter table books
    alter column owner_user_id set not null;

alter table books
    add constraint uk_books_tenant_id unique (tenant_id, id);

alter table books
    add constraint fk_books_owner_tenant_membership
        foreign key (tenant_id, owner_user_id)
        references tenant_memberships (tenant_id, user_id);

create table book_collaborators (
    id uuid constraint pk_book_collaborators primary key,
    tenant_id uuid not null,
    book_id uuid not null,
    user_id uuid not null,
    created_at timestamptz not null,
    created_by_user_id uuid not null,
    constraint fk_book_collaborators_book
        foreign key (tenant_id, book_id)
        references books (tenant_id, id)
        on delete cascade,
    constraint fk_book_collaborators_membership
        foreign key (tenant_id, user_id)
        references tenant_memberships (tenant_id, user_id)
        on delete cascade,
    constraint fk_book_collaborators_created_by_user
        foreign key (created_by_user_id)
        references users (id),
    constraint uk_book_collaborators_book_user
        unique (book_id, user_id)
);

create index idx_book_collaborators_tenant_user_book
    on book_collaborators (tenant_id, user_id, book_id);
