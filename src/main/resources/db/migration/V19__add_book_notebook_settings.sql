create table book_notebook_settings (
    book_id uuid primary key references books(id) on delete cascade,
    defaults_initialized_at timestamptz not null
);

insert into book_notebook_settings (book_id, defaults_initialized_at)
select distinct book_id, current_timestamp
from notebook_categories
on conflict (book_id) do nothing;

alter table notebook_categories
    drop column is_default;
