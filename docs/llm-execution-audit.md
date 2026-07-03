# LLM Execution Audit Foundation

Technical notes for `com.iwrite.llm` (Issue #59).

## Purpose

Every product LLM call must run through `LlmExecutionGateway.execute(spec, call)`.
The gateway centralizes provider invocation, timing, error classification, token
usage collection, optional cost estimation, sanitized logging, and persistence of
one `llm_execution_audits` row per logical execution. Product features must not
duplicate any of this.

The generic `audit_logs` table is unchanged and keeps recording coarse
domain-level operations; `llm_execution_audits` is the specialized model for LLM
operational metadata.

## Metadata that is stored

Per execution: tenant ID, user ID, feature identifier, provider identifier,
model identifier, readable prompt version (for example `scene-analysis:v1`),
trace/correlation ID, optional related resource type and ID, start and
completion timestamps, latency in milliseconds, execution status, stable error
category, input/output/total token counts when the provider reports them,
optional estimated cost with its currency, and a fallback indicator.

## Data that is never stored

- manuscript or scene content;
- complete prompts (system or user) — only the stable prompt version identifier;
- complete model responses;
- API keys, authorization tokens, or passwords;
- exception messages or stack traces — failures persist only a stable
  `error_category` value.

This is enforced structurally: the gateway API (`LlmExecutionSpec`,
`LlmCallResult`) has no field able to carry free text. Identifier fields are
validated against strict short, whitespace-free patterns, so content cannot be
smuggled into the audit trail. Failures persist an enum category, never the
exception text.

## Manuscript and PII handling

The gateway never receives prompt or response content; the calling feature owns
both and hands the gateway only the typed result plus provider metadata. Logs
emitted by the gateway contain the same bounded metadata as the table plus the
trace ID (`llmTraceId` in the MDC), and never content. Tenant ID and user ID are
the only personal references stored; they are required for tenant isolation and
future data-subject requests.

## Status and error model

Rows are created as `STARTED` before the provider call and finished with exactly
one terminal state: `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `UNAVAILABLE`,
`INVALID_RESPONSE`, or `DISABLED`. The terminal update is a conditional
`UPDATE ... WHERE status = 'STARTED'`, so the first completion wins; duplicate,
concurrent, or delayed completions cannot overwrite a recorded outcome
(a success can never become a failure afterwards).

Audit persistence failures have explicit behavior: a failed start write aborts
the execution before the provider is called (fail-closed), while a failed
terminal write preserves the product outcome and logs the failure with the
trace ID (the row then remains `STARTED`, which is detectable operationally).

## Transactions

`LlmExecutionGateway.execute` rejects calls made inside an active database
transaction. Audit writes run in short `REQUIRES_NEW` transactions, so no
database connection is held open during the external provider call.

## Token usage and cost

Token counts come only from provider-reported usage metadata (Spring AI
`Usage`); all-zero usage is treated as "not reported" and persisted as `NULL`.
Counts are never estimated. Cost estimation is optional and configured per
provider/model under `iwrite.ai.audit.pricing`; missing pricing yields `NULL`
(never zero) and never fails the execution.

## Retention and deletion

Initial policy: audit rows are retained indefinitely; no automated purge job
exists yet. Because rows contain only operational metadata, retention risk is
limited to tenant/user identifiers. When tenant or user deletion is
implemented, `llm_execution_audits` rows for the deleted tenant
(`tenant_id` index) must be deleted or anonymized in the same process as
`audit_logs`. A time-based retention job (for example, delete rows older than
12 months) is expected follow-up work and is supported by the
`(tenant_id, started_at)` index.

## Current limitations

- Individual provider retry attempts are not modeled as separate rows; the
  execution row covers the whole logical call including internal Spring AI
  retries. The model can be extended later with an attempts table keyed by
  `trace_id`.
- Scene analysis does not yet route through the gateway; that migration is the
  next PR (Issue #60).
- No aggregated metrics endpoint or dashboard; queries can use the
  tenant/date, feature/date, trace and resource indexes.
- Cost estimation requires a manually maintained pricing table; there is no
  automatic price discovery.
