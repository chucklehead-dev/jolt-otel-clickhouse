# Trace-affinity followthrough

This is a completed research snapshot using existing SDK/exporter APIs, not
a new supported processor or product default. See [results and limitations](sdk-trace-affinity-results-20261018.md).

With 625 traces and eight sibling spans per trace, two serial exporter lanes
retained higher saturated capacity (44.6k rows/s versus 28.9k/27.9k controls).
At nominal 20k/s paced arrivals, all layouts delivered every span, but two
lanes produced more smaller exports and higher allocation without a rate win.
Every one of 1,050,000 physical rows and typed values passed fresh recovery.

These exact measured sources assume the original `/home/chuck/ai-src` layout,
pinned dependency worktrees, compiler and native artifacts. Cloning this branch
alone is not a portable reproduction setup. Output guards deliberately refuse
to overwrite completed receipts. Only source and small scalar receipts are
preserved; all six native stores were removed after independent verification.

Pure tests include known-bad routing controls. Callback order is checked per
trace, not promised as global or physical storage order. The actual fixture's
reader sort-key uniqueness and sampled-bit row parity have separate controls.
A single hot trace cannot benefit from trace-affinity parallelism. No app,
socket, Langfuse, S3, Rust-parity, end-to-end latency or sustained-tail claim.

Exporter #136 tracks product gates. OTel #62 separately investigates a small
worker scheduling change identified by these results; that candidate is not
adopted and its throughput/latency tradeoffs still need qualification.
