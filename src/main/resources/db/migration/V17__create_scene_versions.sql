alter table scenes
    add column content_revision bigint not null default 0;

create table scene_versions (
    id uuid primary key,
    book_id uuid not null references books(id) on delete cascade,
    scene_id uuid references scenes(id) on delete set null,
    original_scene_id uuid not null,
    scene_title_snapshot varchar(255) not null,
    content_json text,
    content_text text,
    word_count integer not null default 0,
    source varchar(40) not null,
    content_hash varchar(64) not null,
    created_at timestamptz not null
);

create index idx_scene_versions_scene_created_at on scene_versions(scene_id, created_at desc);
create index idx_scene_versions_book_original_scene_created_at on scene_versions(book_id, original_scene_id, created_at desc);
create index idx_scene_versions_scene_content_hash on scene_versions(scene_id, content_hash);
