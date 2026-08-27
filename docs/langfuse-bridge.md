# Langfuse bridge boundary

Grounded against `langfuse/langfuse` commit
`0f062bec18021298a038e82b2455726bba234b27` (2026-08-26 checkout).

Langfuse is not a generic OTel viewer and its ClickHouse tables are application
state. Current migrations introduce the v4 `events_full` and `events_core`
tables with project-scoped trace/span IDs, LLM observation types, model and
prompt metadata, token usage, decimal cost maps, tool calls, inputs/outputs,
deletion/version timestamps, and replacing-merge semantics. Writing ordinary
OTel rows directly into those managed tables would bypass Langfuse ingestion,
authorization, update, cost, and migration contracts.

The safe path is a separate projection layer after the embedded OTel pipeline
is stable:

1. Keep canonical OTel spans/logs as the source-neutral observability record.
2. Define an explicit opt-in `LangfuseEvent` mapping for agent/LLM spans. It
   requires a project ID and maps run/trace IDs, parent span IDs, observation
   type (`SPAN`, `GENERATION`, `AGENT`, `TOOL`, etc.), model, prompt reference,
   usage, costs, tool names, environment, session/user IDs, and bounded metadata.
3. Make input/output capture off by default and independently redacted; OTel
   instrumentation must never imply prompt or completion retention.
4. First export through Langfuse's supported ingestion API/OTel endpoint so its
   worker owns normalization and migrations. A direct embedded projection is a
   later local-only adapter with its own versioned tables or compatibility
   tests against a pinned Langfuse migration set.
5. Validate with a real Langfuse deployment: one agent trace, nested generation
   and tool observations, token/cost totals, and updates/deletes visible through
   the UI. Do not use `events_core`/`events_full` as a stable public API without
   an upstream contract.

This keeps ClickStack useful for generic operations telemetry and Langfuse
useful for LLM semantics without forcing either data model into the other's
tables.
