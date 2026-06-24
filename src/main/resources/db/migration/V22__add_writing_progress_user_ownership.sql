alter table book_writing_schedules
    add column user_id uuid;

alter table book_daily_writing_progress
    add column user_id uuid;

alter table book_word_count_events
    add column actor_user_id uuid;

do $$
declare
    legacy_tenant_id uuid := '00000000-0000-0000-0000-000000000001';
    legacy_user_id uuid := '00000000-0000-0000-0000-000000000002';
begin
    if not exists (
        select 1
        from tenant_memberships
        where tenant_id = legacy_tenant_id
          and user_id = legacy_user_id
          and role = 'OWNER'
    ) then
        raise exception 'Cannot backfill writing ownership: legacy tenant owner membership is missing';
    end if;

    if exists (
        select 1
        from book_writing_schedules schedule
        join books book on book.id = schedule.book_id
        where book.tenant_id is null
    ) or exists (
        select 1
        from book_daily_writing_progress progress
        join books book on book.id = progress.book_id
        where book.tenant_id is null
    ) or exists (
        select 1
        from book_word_count_events event
        join books book on book.id = event.book_id
        where book.tenant_id is null
    ) then
        raise exception 'Cannot backfill writing ownership: book tenant is missing';
    end if;

    if exists (
        select 1
        from book_writing_schedules schedule
        join books book on book.id = schedule.book_id
        where book.tenant_id <> legacy_tenant_id
    ) or exists (
        select 1
        from book_daily_writing_progress progress
        join books book on book.id = progress.book_id
        where book.tenant_id <> legacy_tenant_id
    ) or exists (
        select 1
        from book_word_count_events event
        join books book on book.id = event.book_id
        where book.tenant_id <> legacy_tenant_id
    ) then
        raise exception 'Cannot backfill writing ownership: no deterministic legacy user exists for a non-legacy tenant';
    end if;

    update book_writing_schedules
    set user_id = legacy_user_id
    where user_id is null;

    update book_daily_writing_progress
    set user_id = legacy_user_id
    where user_id is null;

    update book_word_count_events
    set actor_user_id = legacy_user_id
    where actor_user_id is null;
end $$;

drop index if exists uk_book_writing_schedules_one_active;

alter table book_writing_schedules
    drop constraint if exists ex_book_writing_schedules_no_overlap,
    alter column user_id set not null,
    add constraint fk_book_writing_schedules_user
        foreign key (user_id) references users (id);

drop index if exists idx_book_writing_schedules_book_period;

create unique index uk_book_writing_schedules_user_book_one_active
    on book_writing_schedules (user_id, book_id)
    where effective_to is null;

alter table book_writing_schedules
    add constraint ex_book_writing_schedules_user_book_no_overlap
    exclude using gist (
        user_id with =,
        book_id with =,
        daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') with &&
    );

create index idx_book_writing_schedules_user_book_period
    on book_writing_schedules (user_id, book_id, effective_from, effective_to);

alter table book_daily_writing_progress
    drop constraint uk_book_daily_writing_progress_book_date,
    alter column user_id set not null,
    add constraint fk_book_daily_writing_progress_user
        foreign key (user_id) references users (id),
    add constraint uk_book_daily_writing_progress_user_book_date
        unique (user_id, book_id, progress_date);

drop index if exists idx_book_daily_writing_progress_book_date;

alter table book_word_count_events
    alter column actor_user_id set not null,
    add constraint fk_book_word_count_events_actor_user
        foreign key (actor_user_id) references users (id);

create index idx_book_word_count_events_actor_book_created
    on book_word_count_events (actor_user_id, book_id, created_at);
