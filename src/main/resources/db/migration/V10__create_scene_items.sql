create table scene_items (
    scene_id uuid not null references scenes(id) on delete cascade,
    item_id uuid not null references items(id) on delete cascade,
    primary key (scene_id, item_id)
);

create index idx_scene_items_scene_id on scene_items(scene_id);
create index idx_scene_items_item_id on scene_items(item_id);
