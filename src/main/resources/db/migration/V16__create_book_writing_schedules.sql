create extension if not exists btree_gist;

create table book_writing_schedules (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    effective_from date not null,
    effective_to date,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint chk_book_writing_schedules_valid_period
        check (effective_to is null or effective_to > effective_from)
);

create table book_writing_schedule_days (
    schedule_id uuid not null references book_writing_schedules(id) on delete cascade,
    day_of_week varchar(16) not null,
    primary key (schedule_id, day_of_week),
    constraint chk_book_writing_schedule_days_day
        check (day_of_week in ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'))
);

create unique index uk_book_writing_schedules_one_active
    on book_writing_schedules(book_id)
    where effective_to is null;

alter table book_writing_schedules
    add constraint ex_book_writing_schedules_no_overlap
        exclude using gist (
            book_id with =,
            daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[)') with &&
        );

create index idx_book_writing_schedules_book_period
    on book_writing_schedules(book_id, effective_from, effective_to);

with initial_schedules as (
    select
        (
            substr(md5(books.id::text || ':initial-writing-schedule'), 1, 8) || '-' ||
            substr(md5(books.id::text || ':initial-writing-schedule'), 9, 4) || '-' ||
            substr(md5(books.id::text || ':initial-writing-schedule'), 13, 4) || '-' ||
            substr(md5(books.id::text || ':initial-writing-schedule'), 17, 4) || '-' ||
            substr(md5(books.id::text || ':initial-writing-schedule'), 21, 12)
        )::uuid as id,
        books.id as book_id,
        case
            when first_progress.first_progress_date is not null
                 and first_progress.first_progress_date < books.created_at::date
                then first_progress.first_progress_date
            else books.created_at::date
        end as effective_from
    from books
    left join (
        select book_id, min(progress_date) as first_progress_date
        from book_daily_writing_progress
        group by book_id
    ) first_progress on first_progress.book_id = books.id
), inserted_schedules as (
    insert into book_writing_schedules (id, book_id, effective_from, effective_to, created_at, updated_at)
    select id, book_id, effective_from, null, now(), now()
    from initial_schedules
    returning id
)
insert into book_writing_schedule_days (schedule_id, day_of_week)
select inserted_schedules.id, days.day_of_week
from inserted_schedules
cross join (
    values
        ('MONDAY'),
        ('TUESDAY'),
        ('WEDNESDAY'),
        ('THURSDAY'),
        ('FRIDAY'),
        ('SATURDAY'),
        ('SUNDAY')
) days(day_of_week);
