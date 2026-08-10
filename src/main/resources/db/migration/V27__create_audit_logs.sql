create table audit_logs (
    id uuid constraint pk_audit_logs primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    action varchar(64) not null,
    resource_type varchar(64) not null,
    resource_id uuid,
    occurred_at timestamptz not null,
    result varchar(16) not null,
    constraint chk_audit_logs_result check (result in ('SUCCEEDED', 'FAILED'))
);

create index idx_audit_logs_tenant_occurred_at
    on audit_logs (tenant_id, occurred_at desc);

create index idx_audit_logs_resource
    on audit_logs (tenant_id, resource_type, resource_id, occurred_at desc);
