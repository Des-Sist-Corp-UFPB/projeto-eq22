create table llm_execution_audits (
    id uuid constraint pk_llm_execution_audits primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    feature varchar(64) not null,
    provider varchar(64) not null,
    model varchar(128),
    prompt_version varchar(64) not null,
    trace_id uuid not null,
    resource_type varchar(64),
    resource_id uuid,
    started_at timestamptz not null,
    completed_at timestamptz,
    latency_ms bigint,
    status varchar(32) not null,
    error_category varchar(64),
    input_tokens integer,
    output_tokens integer,
    total_tokens integer,
    estimated_cost numeric(12, 6),
    cost_currency varchar(8),
    fallback_used boolean not null default false,
    constraint chk_llm_execution_audits_status check (
        status in ('STARTED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'UNAVAILABLE', 'INVALID_RESPONSE', 'DISABLED')
    ),
    constraint chk_llm_execution_audits_error_category check (
        error_category is null or error_category in (
            'PROVIDER_TIMEOUT',
            'PROVIDER_UNAVAILABLE',
            'PROVIDER_REQUEST_REJECTED',
            'INVALID_STRUCTURED_RESPONSE',
            'CONFIGURATION_ERROR',
            'FEATURE_DISABLED',
            'AUDIT_PERSISTENCE_FAILURE',
            'INTERNAL_EXECUTION_ERROR'
        )
    ),
    constraint chk_llm_execution_audits_completion check (
        (status = 'STARTED' and completed_at is null and latency_ms is null and error_category is null)
        or (status = 'SUCCEEDED' and completed_at is not null and latency_ms is not null and error_category is null)
        or (
            status in ('FAILED', 'TIMED_OUT', 'UNAVAILABLE', 'INVALID_RESPONSE', 'DISABLED')
            and completed_at is not null
            and latency_ms is not null
            and error_category is not null
        )
    ),
    constraint chk_llm_execution_audits_resource check (
        resource_id is null or resource_type is not null
    ),
    constraint chk_llm_execution_audits_latency check (
        latency_ms is null or latency_ms >= 0
    ),
    constraint chk_llm_execution_audits_tokens check (
        (input_tokens is null or input_tokens >= 0)
        and (output_tokens is null or output_tokens >= 0)
        and (total_tokens is null or total_tokens >= 0)
    ),
    constraint chk_llm_execution_audits_cost check (
        (estimated_cost is null and cost_currency is null)
        or (estimated_cost is not null and estimated_cost >= 0 and cost_currency is not null)
    )
);

create index idx_llm_execution_audits_tenant_started_at
    on llm_execution_audits (tenant_id, started_at desc);

create index idx_llm_execution_audits_feature_started_at
    on llm_execution_audits (feature, started_at desc);

create index idx_llm_execution_audits_trace_id
    on llm_execution_audits (trace_id);

create index idx_llm_execution_audits_resource
    on llm_execution_audits (tenant_id, resource_type, resource_id, started_at desc);
