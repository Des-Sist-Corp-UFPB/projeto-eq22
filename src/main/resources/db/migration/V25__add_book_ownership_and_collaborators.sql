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

insert into book_collaborators (id, tenant_id, book_id, user_id, created_at, created_by_user_id)
select (
           substring(legacy_collaborator.hash from 1 for 8)
               || '-' || substring(legacy_collaborator.hash from 9 for 4)
               || '-' || substring(legacy_collaborator.hash from 13 for 4)
               || '-' || substring(legacy_collaborator.hash from 17 for 4)
               || '-' || substring(legacy_collaborator.hash from 21 for 12)
       )::uuid,
       legacy_collaborator.tenant_id,
       legacy_collaborator.book_id,
       legacy_collaborator.user_id,
       current_timestamp,
       legacy_collaborator.owner_user_id
from (
    select book.tenant_id,
           book.id as book_id,
           book.owner_user_id,
           membership.user_id,
           md5(book.id::text || ':' || membership.user_id::text) as hash
    from books book
    join tenant_memberships membership
      on membership.tenant_id = book.tenant_id
    where membership.user_id <> book.owner_user_id
) legacy_collaborator;

create index idx_book_collaborators_tenant_user_book
    on book_collaborators (tenant_id, user_id, book_id);
