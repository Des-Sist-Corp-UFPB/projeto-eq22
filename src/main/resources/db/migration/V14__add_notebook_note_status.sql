alter table notebook_notes
    add column status varchar(20) not null default 'OPEN';

alter table notebook_notes
    add constraint chk_notebook_notes_status check (status in ('OPEN', 'RESOLVED'));
