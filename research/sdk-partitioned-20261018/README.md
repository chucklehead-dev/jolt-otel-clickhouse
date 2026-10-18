# Partitioned SDK capacity research snapshot

This branch preserves a completed local experiment, not a supported new
processor, production code change, or proposed default. Start with
[the results and limitations](sdk-partitioned-results-20261018.md).

Two distinct exporters with serial SDK workers sharing one owned Durable
connection measured 41.5k confirmed rows/s, between single-lane controls at
26.8k and 25.0k. All 525,000 physical rows and typed values passed independent
fresh-process recovery. Callback latency increased; this is not a single-caller
speedup, sustained-tail qualification, application-ingress proof or Rust parity.

The measured driver and launcher are byte-for-byte snapshots. They assume the
original `/home/chuck/ai-src` workspace, exact pinned dependency worktrees and
checksummed runtime/native artifacts; cloning this branch alone does not
provide those external prerequisites. Shell paths deliberately remain as
measured rather than claiming a new portable runner was qualified. Do not run
them blindly: output-name guards intentionally reject overwriting old receipts.
The source records the sole connection owner, independent exporter instances,
bounded queues, stable-settlement cleanup, exact ACK accounting and limits.

Only small scalar writer/reader/validation receipts are included. No native
stores, WAL, captured telemetry payloads, credentials or raw process/cloud logs. Generated
stores (~809 MiB total across three serial runs) were removed after proof.

Related: exporter #136 and jolt-chdb #259. Further product work needs real
trace-affinity/arrival tests, explicit ordering and aggregate in-flight bounds,
failure/lifecycle controls and an agreed API. Single-lane encoding/allocation
improvements remain a separate priority. No upstream submission or PR here.
