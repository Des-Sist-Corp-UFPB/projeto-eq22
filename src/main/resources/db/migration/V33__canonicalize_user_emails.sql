-- V32 normalized legacy emails using PostgreSQL's trim(), which only strips plain ASCII spaces
-- (0x20) from both ends. Java's String.trim() — what EmailNormalizer.normalize() actually calls on
-- every write and lookup path — strips a wider range: any character whose code point is <= 0x20,
-- which also covers TAB, CR, LF and the other C0 control characters. A legacy row padded with one
-- of those (never 0x20 alone) could satisfy V32's own chk_users_email_normalized check constraint,
-- built on that same narrower trim(), while still not matching what the application computes for the
-- same address — invisible to a clean login attempt, and not caught by uk_users_email as a duplicate
-- of the clean address either, since the stored bytes differ. This migration replaces V32's
-- constraint with one built on a canonical form that is actually equivalent to Java's trim().
--
-- 0x00 is deliberately excluded from that range: PostgreSQL text/varchar cannot store an embedded
-- NUL byte at all (the write itself is rejected), so no existing users.email row can ever actually
-- contain one — the reachable range Java's trim() strips is exactly 0x01-0x20 in a real row here.
--
-- Not editing V32 in place: it may already be applied against local and test databases, and Flyway
-- treats a change to an already-applied migration's content as a checksum mismatch, not a fix.

alter table users
    drop constraint chk_users_email_normalized;

-- Fails the whole migration, before anything is altered, if two existing rows would collapse to the
-- same canonical email — a real duplicate-account conflict a human has to resolve, not something a
-- migration may silently pick a winner for. Mirrors V32's own collision guard, against the wider
-- canonical form.
do $$
declare
    colliding_emails integer;
begin
    select count(*) into colliding_emails
    from (
        select lower(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'))
        from users
        group by lower(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'))
        having count(*) > 1
    ) as collisions;

    if colliding_emails > 0 then
        raise exception 'V33 aborted: % existing email(s) collide once canonically normalized (String.trim()-equivalent + lowercase); resolve the duplicate account(s) before re-running this migration', colliding_emails;
    end if;
end $$;

update users
set email = lower(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'))
where email <> lower(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'));

-- Ids, credentials, memberships, tenants and books are untouched: this statement only ever rewrites
-- the users.email column of existing rows, never a row's identity.
alter table users
    add constraint chk_users_email_normalized
        check (email = lower(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g')));
