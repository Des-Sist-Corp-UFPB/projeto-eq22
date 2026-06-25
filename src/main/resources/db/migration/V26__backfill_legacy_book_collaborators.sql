insert into book_collaborators (
id,
tenant_id,
book_id,
user_id,
created_at,
created_by_user_id
)
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
) legacy_collaborator
on conflict (book_id, user_id) do nothing;
