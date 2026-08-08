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
--
-- #149 review: this slice also restricts account emails to ASCII (see EmailNormalizer's class doc).
-- V32 (as amended by that same review) already refuses to run past a non-ASCII legacy email, so in
-- the normal upgrade path no such row can reach this point. This guard is only reachable by a
-- database that already applied V32 *before* that amendment (requiring a documented `flyway repair`
-- to accept the new checksum, never run automatically) — mirrored here as defense in depth, exactly
-- like the collision guard below mirrors V32's.
--
-- #149 review (fresh finding, round 7): both this migration's own canonicalization and V32's now use
-- translate() to lowercase the 26 ASCII letters, not PostgreSQL's lower() — lower() is
-- collation-dependent even for ASCII input (e.g. a database with Turkish casing rules maps 'I' to the
-- non-ASCII 'ı'), which could otherwise make V32 commit a non-ASCII row that this migration's own
-- chk_users_email_ascii would then reject, wedging every subsequent startup on a failed migration.
-- translate() only ever touches those 26 code points and is deterministic regardless of collation.

alter table users
    drop constraint chk_users_email_normalized;

do $$
declare
    non_ascii_emails integer;
begin
    select count(*) into non_ascii_emails
    from users
    where email ~ '[^\x00-\x7F]';

    if non_ascii_emails > 0 then
        raise exception 'V33 aborted: % existing user(s) have a non-ASCII email; this slice only supports ASCII account emails. Resolve each account manually (confirm and update its email) before re-running this migration', non_ascii_emails;
    end if;
end $$;

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
        select translate(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
        from users
        group by translate(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
        having count(*) > 1
    ) as collisions;

    if colliding_emails > 0 then
        raise exception 'V33 aborted: % existing email(s) collide once canonically normalized (String.trim()-equivalent + lowercase); resolve the duplicate account(s) before re-running this migration', colliding_emails;
    end if;
end $$;

update users
set email = translate(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')
where email <> translate(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz');

-- Ids, credentials, memberships, tenants and books are untouched: this statement only ever rewrites
-- the users.email column of existing rows, never a row's identity.
alter table users
    add constraint chk_users_email_normalized
        check (email = translate(regexp_replace(email, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'));

-- #149 review: closes the ASCII-only policy at the database level, the same way
-- chk_users_email_normalized closes the case/padding one above — no future write (application bug,
-- manual SQL, another migration) can introduce a non-ASCII users.email row again. Every existing row
-- already passed the guard above, so this can never fail against pre-existing data.
alter table users
    add constraint chk_users_email_ascii check (email ~ '^[\x00-\x7F]*$');
