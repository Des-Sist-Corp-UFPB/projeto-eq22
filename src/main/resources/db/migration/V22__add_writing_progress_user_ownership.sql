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
    -- Backfill rule:
    -- * V20 legacy tenant rows use the deterministic V20 legacy user.
    -- * Non-legacy tenant rows use the tenant's single OWNER membership.
    -- * Missing OWNERs or multiple OWNERs are ambiguous and fail before NOT NULL/FK enforcement.
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
        with affected_books as (
            select distinct book.id, book.tenant_id
            from book_writing_schedules schedule
            join books book on book.id = schedule.book_id
            where book.tenant_id <> legacy_tenant_id
            union
            select distinct book.id, book.tenant_id
            from book_daily_writing_progress progress
            join books book on book.id = progress.book_id
            where book.tenant_id <> legacy_tenant_id
            union
            select distinct book.id, book.tenant_id
            from book_word_count_events event
            join books book on book.id = event.book_id
            where book.tenant_id <> legacy_tenant_id
        )
        select 1
        from affected_books affected
        left join tenant_memberships membership
            on membership.tenant_id = affected.tenant_id
           and membership.role = 'OWNER'
        group by affected.id
        having count(membership.user_id) = 0
    ) then
        raise exception 'Cannot backfill writing ownership: non-legacy book tenant has no OWNER membership';
    end if;

    if exists (
        with affected_books as (
            select distinct book.id, book.tenant_id
            from book_writing_schedules schedule
            join books book on book.id = schedule.book_id
            where book.tenant_id <> legacy_tenant_id
            union
            select distinct book.id, book.tenant_id
            from book_daily_writing_progress progress
            join books book on book.id = progress.book_id
            where book.tenant_id <> legacy_tenant_id
            union
            select distinct book.id, book.tenant_id
            from book_word_count_events event
            join books book on book.id = event.book_id
            where book.tenant_id <> legacy_tenant_id
        )
        select 1
        from affected_books affected
        join tenant_memberships membership
            on membership.tenant_id = affected.tenant_id
           and membership.role = 'OWNER'
        group by affected.id
        having count(membership.user_id) > 1
    ) then
        raise exception 'Cannot backfill writing ownership: non-legacy book tenant has multiple OWNER memberships';
    end if;

    update book_writing_schedules schedule
    set user_id = case
        when book.tenant_id = legacy_tenant_id then legacy_user_id
        else membership.user_id
    end
    from books book
    left join tenant_memberships membership
        on membership.tenant_id = book.tenant_id
       and membership.role = 'OWNER'
    where book.id = schedule.book_id
      and schedule.user_id is null;

    update book_daily_writing_progress progress
    set user_id = case
        when book.tenant_id = legacy_tenant_id then legacy_user_id
        else membership.user_id
    end
    from books book
    left join tenant_memberships membership
        on membership.tenant_id = book.tenant_id
       and membership.role = 'OWNER'
    where book.id = progress.book_id
      and progress.user_id is null;

    update book_word_count_events event
    set actor_user_id = case
        when book.tenant_id = legacy_tenant_id then legacy_user_id
        else membership.user_id
    end
    from books book
    left join tenant_memberships membership
        on membership.tenant_id = book.tenant_id
       and membership.role = 'OWNER'
    where book.id = event.book_id
      and event.actor_user_id is null;

    if exists (
        select 1
        from book_writing_schedules schedule
        where schedule.user_id is null
    ) or exists (
        select 1
        from book_daily_writing_progress progress
        where progress.user_id is null
    ) or exists (
        select 1
        from book_word_count_events event
        where event.actor_user_id is null
    ) then
        raise exception 'Cannot backfill writing ownership: unresolved writing ownership remains';
    end if;

    if exists (
        select 1
        from book_writing_schedules schedule
        join books book on book.id = schedule.book_id
        left join tenant_memberships membership
            on membership.tenant_id = book.tenant_id
           and membership.user_id = schedule.user_id
        where membership.id is null
    ) or exists (
        select 1
        from book_daily_writing_progress progress
        join books book on book.id = progress.book_id
        left join tenant_memberships membership
            on membership.tenant_id = book.tenant_id
           and membership.user_id = progress.user_id
        where membership.id is null
    ) or exists (
        select 1
        from book_word_count_events event
        join books book on book.id = event.book_id
        left join tenant_memberships membership
            on membership.tenant_id = book.tenant_id
           and membership.user_id = event.actor_user_id
        where membership.id is null
    ) then
        raise exception 'Cannot backfill writing ownership: resolved user is not a member of the book tenant';
    end if;
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
