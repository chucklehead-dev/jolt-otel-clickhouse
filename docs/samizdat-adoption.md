# Samizdat adoption seam

Grounded against `yogthos/samizdat` commit
`5aa94769160a92ffb5131adf776fdc06f6157405` (2026-08-26 checkout).

Samizdat already has two different things named telemetry: durable operational
facts in SQLite and `samizdat.agent.telemetry`, a pure run-health digest for the
supervisor. Neither should be replaced. OTel/chDB is a third, derived
observability projection whose loss must never affect the journal or agent loop.

## First adoption slice

1. Add pinned `jolt-otel`, `jolt-chdb`, and `jolt-otel-clickhouse` dependencies.
   Keep the durable `jolt-lang/db` SQLite connection unchanged.
2. Extend `samizdat.system/start!` after the SQLite project bind and before the
   HTTP server starts: open a separate chDB connection, construct a shared
   exporter, initialize the SDK with `:logs? true`, and store all three handles
   in the system map.
3. Extend `stop!` in dependency order: stop active runs and HTTP, flush/shutdown
   OTel, close chDB, then close the authoritative SQLite store. A failed exporter
   shutdown remains a warning and cannot skip SQLite close.
4. Wrap `samizdat.server/handler` at the adapter boundary with server spans.
   Instrument `samizdat.llm.client` provider calls as client spans with provider,
   model, token/usage fields when available, status, and latency. Never record
   API keys, prompt bodies, model responses, tool arguments, environment maps,
   or filesystem contents by default.
5. Add spans around cell execution and tool dispatch using stable low-cardinality
   names. Put `run.id`, `branch.id`, `task.id`, `cell.id`, `tool.name`, outcome,
   and bounded counters on attributes. Record exceptions through the OTel API.
   Existing `clojure.tools.logging` calls become correlated automatically via
   the additive bridge.

## Event projection

The durable journal remains the replay authority. Subscribe to
`samizdat.events` with its existing sliding-buffer semantics and translate a
small allowlist of lifecycle events to span events or structured logs. A dropped
subscriber event is acceptable because SQLite remains complete; expose a
counter for projection drops. Do not make journal append wait for chDB.

## Agent-facing queries

Add read-only tools only after the generic demo API is stable:

- recent failing traces for this run;
- correlated logs for a trace ID;
- provider latency/error summaries grouped by model;
- tool latency/error summaries grouped by tool name;
- one trace waterfall with parent/child relationships.

These query chDB through the same `jdbc.core`/HoneySQL surface as the demo. They
must use bounded time windows and row limits. The supervisor's current compact
health digest stays pure and deterministic; it may consume bounded aggregates,
but not raw unbounded logs or spans.

## Gates

- With export disabled, existing Samizdat tests and startup behavior are
  unchanged.
- Export failures never fail a tool call, provider call, journal write, or
  shutdown of the SQLite authority.
- A fixture run produces a root run span, child cell/provider/tool spans, and
  correlated logs with no sensitive payload fields.
- Restarting with persistent chDB retains prior observability while SQLite
  resume remains authoritative.
- A slow/full exporter proves bounded queues and measurable drops.
