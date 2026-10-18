Current compatible-path candidate: [exact source diff](https://github.com/chucklehead-dev/jolt-otel-clickhouse/compare/e3750302c44cd10273c6e1277dc3912975cac8f5...e1723f4e836497638c89ec8c63fe9dbe031aa1fa).
It avoids repeated normalizer/membership calls for built-in string-key maps
under defining-owner normalizer identity and live stock membership/string
predicate guards. Changed policy, non-string aliases and unsupported shapes
retain the existing ordered collector. No JSONWriter, descriptor, value/status,
schema, payload limit or Durable acknowledgement contract is weakened.

Focused native projector suite:22tests310assertions green, including a poisoned
fallback control proving real fast selection and changed-policy fallback.
Claude's first source-only review timed out with no report and is not credited;
a narrower guard/fallback review is pending. Branch is not a PR/merge. The full
stock-Jolt0.8.22 maintained aggregate is running; no green aggregate claim yet.

Two paired pure5k/wide16 screens preserve full typed-vector and UTF8 parity.
First: typed projection p50 12.93->10.84ms; fresh projection+encoding
75.23->72.69ms. Added predicate-guard repeat:14.48->14.19ms and97.76->82.13ms.
Managed allocation falls~32B/row in both; timing magnitude varies materially.
These are component screens with private fixture plan compilation, NOT actual
schema-installation, confirmed-throughput or p99 qualification.

Actual serial SDK->local Durable, frozen qualified1ae/JSON8d/SDK37/native26.9,
5k batches: control/candidate/control26.52k/25.85k/28.82krows/s. All32callbacks,
zero SDK failures/drops, and full175k physical/typed rows recovered per arm
(525k total) by an unchanged exporter/stock-reader process. Candidate also saves
~32B/row in the whole writer window. Candidate is below both controls:
**no end-to-end speedup or no-regression qualification is claimed**, and it
remains unmerged while the difference is investigated. All77GCs/arm; GC wall
97/90/83ms does not account for the full6.03/6.19/5.55s window difference.
Calling-thread CPU is NOT exporter-worker/native CPU attribution. Do not blame
GC/host noise or infer an intrinsic regression without further attribution.
Each266MiB generated store removed after successful independent
proof plus cmp-verified scalar receipt copies. No local S3/Lemonade traffic.

Two larger alternatives were also tested and rejected, not silently lost:
single-pass attribute clone had unchanged allocation and flat timing; eager
virtual-map staging passed targeted writer/classification/ordering controls but
remained slower and allocated160B/row more after reducing closure overhead.
They are preserved in local research commits944497c/c29829d and are not adopted
dependencies. Deferred conversion additionally fails actual callback ordering.

Keep #91 open. Current work is cumulative over existing JSON/compiler fixes,
not a return to old stock baselines or a claim that this small slice closes the
Rust gap. Optional canonical-OTel mode is a separate unanswered product choice;
no altered contract is assumed and compatible/upstream work remains unblocked.
