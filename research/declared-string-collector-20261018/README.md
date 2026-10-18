# Declared-string collector evidence

Code candidate: `57415464e2685703dc8a8d49a017ed5f12317ef0`.
Allocation benefit is measured; an end-to-end throughput benefit is not established.

See [checkpoint and exact validation](declared-string-collector-progress-20261018.md)
and [proposed PR text](declared-string-collector-pr-text-20261018.md).

Machine-specific scripts, source snapshots and bounded scalar/test receipts only.
No database stores, encoded SQL/payloads, credentials or compiled runtimes. Scripts
refer to pinned workspace paths; this is not a portable benchmark task. Do not
silently substitute current branches or re-run terminal jobs from old handles.

This research checkpoint is separate from the reviewed source branch so it does
not invalidate its exact-head CI or enlarge the proposed production diff.

Files named `declared-string-keys-*20261017*` are recovered historical context:
candidate `8a8fe12` already explored binding plus a per-key normalizer shortcut,
but retained per-key membership and did not establish a Durable gain. Those
receipts are not current baselines or part of the Oct18 sample populations.
