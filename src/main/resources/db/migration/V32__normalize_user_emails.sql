-- Every write path now normalizes email via EmailNormalizer (trim + lowercase) before it ever
-- reaches the database, but rows created before that existed may still carry mixed case or
-- padding. This backfills them and then closes the gap at the database level (#143 review).

-- Fails the whole migration, before anything is altered, if two existing rows would collapse to
-- the same normalized email — that is a real duplicate-account conflict a human has to resolve,
-- not something a migration may silently pick a winner for.
do $$
declare
    colliding_emails integer;
begin
    select count(*) into colliding_emails
    from (
        select lower(trim(email))
        from users
        group by lower(trim(email))
        having count(*) > 1
    ) as collisions;

    if colliding_emails > 0 then
        raise exception 'V32 aborted: % existing email(s) collide once normalized (trim + lowercase); resolve the duplicate account(s) before re-running this migration', colliding_emails;
    end if;
end $$;

update users
set email = lower(trim(email))
where email <> lower(trim(email));

-- Ids, credentials, memberships, tenants and books are untouched: this statement only ever
-- rewrites the users.email column of existing rows, never a row's identity.
alter table users
    add constraint chk_users_email_normalized check (email = lower(trim(email)));
