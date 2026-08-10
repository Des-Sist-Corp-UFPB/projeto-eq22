-- V31's own backfill matched the legacy user by exact equality against
-- 'carlos.legacy@iwrite.local', before V32/V33 (later in this same slice) ever ran. On an upgraded
-- installation where that user's stored email had case or padding differences — the exact legacy
-- state V32/V33 exist to repair — V31's exact comparison finds no row and inserts no persona.
-- Nothing retries that backfill once V32/V33 canonicalize the address later in the migration path,
-- leaving that account without a primary persona even after its email is fixed.
--
-- This migration is that retry, running after V32/V33 have already canonicalized the address. Not a
-- V31 edit: V31 must not be made to depend on migrations that run after it, and V31 may already be
-- applied (checksum-locked by Flyway) against installations that upgraded before this round.
--
-- "Does not yet have a persona" — not "does not yet have this exact WRITER row" — is the guard:
-- checking only (user_id, 'WRITER') via the table's own unique constraint would silently skip a
-- user who already has some other persona, and would not tell apart "V31 already handled this user"
-- from "V31 could not find this user". A user who already has any persona row is left untouched
-- either way; a user found by V31 already has exactly the row this migration would otherwise add,
-- so the not-exists guard also keeps this idempotent across repeated runs and consistent with an
-- installation where V31 found the user directly (no duplicate row, ever).
insert into user_personas (id, user_id, persona, is_primary, created_at, updated_at)
select gen_random_uuid(), u.id, 'WRITER', true, now(), now()
from users u
where u.email = 'carlos.legacy@iwrite.local'
  and not exists (
      select 1 from user_personas p where p.user_id = u.id
  )
on conflict (user_id, persona) do nothing;
