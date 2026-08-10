alter table scenes
    add column goal text,
    add column conflict text,
    add column outcome text,
    add column pov_character_id uuid references characters(id) on delete set null,
    add column main_location_id uuid references locations(id) on delete set null;

create index idx_scenes_pov_character_id on scenes(pov_character_id);
create index idx_scenes_main_location_id on scenes(main_location_id);
