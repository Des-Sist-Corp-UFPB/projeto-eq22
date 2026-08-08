-- Credentials are kept out of `users` so authentication data can be rotated or
-- revoked without touching the profile row (#135). Only an adaptive hash is
-- stored; no plaintext and no reversible encryption ever reaches this table.
create table user_credentials (
    user_id uuid constraint pk_user_credentials primary key,
    password_hash varchar(255) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint fk_user_credentials_user foreign key (user_id) references users(id) on delete cascade
);
