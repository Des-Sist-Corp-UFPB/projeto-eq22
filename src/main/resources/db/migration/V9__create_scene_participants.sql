create table scene_participants (
    scene_id uuid not null references scenes(id) on delete cascade,
    character_id uuid not null references characters(id) on delete cascade,
    primary key (scene_id, character_id)
);

create index idx_scene_participants_scene_id on scene_participants(scene_id);
create index idx_scene_participants_character_id on scene_participants(character_id);
