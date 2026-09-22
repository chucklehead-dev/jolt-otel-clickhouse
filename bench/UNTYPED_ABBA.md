# Untyped encoder public Durable qualification

Prepared experiment; do not execute until separately authorized and the
coordinating agent has checked for competing heavy processes.

Baseline production source is `ebac0eb1c0ac070b3591675d15d09acadef81988`.
Candidate is production integration `6fdc6cba06fdb635c09695a5c0a40250c866b03e`.
Both arms resolve identical dependencies; only the exporter source directory
differs. Timed calls use `export/export-spans!` directly with no benchmark wrapper.
The candidate production tests run first, including typed/ordinary bypass and
exact payload controls. Its pre-timing activation witness replaces `span-row`
with a throwing sentinel: the actual public call must still admit exactly the
expected SQL once. This proves it did not silently fall back. The witness runs
outside timing and changes no native state. OTel is fixed at merged
`8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2`, driver at qualified local
`9ec4d6bd5db9a9dc150e3ed6b497e554e3070f75`, compiler at 23d3, Chez at 10.4.1,
native at 26.7.3 and maintained data.json at e7f97a9. Hashes and loaded source
resolution are checked, with unchanged pins checked again at the end.

The proven links ABBA writer/reader helpers retain their scalar-abba namespace
names. Execution is strictly serial A/B/B/A: each arm gets new object storage,
writer/reader scratch and compilation caches. A nonblocking process lock prevents
another cooperating launcher from running concurrently; it cannot detect unrelated
compilers or benchmarks, hence the separate machine check.

Each cell runs five warmups and 100 measured public exports of the frozen 512-row
fixture. Before measurement, exact payload and public SQL must match the maintained
path; the candidate production activation witness is mandatory. Every batch must
add exactly one WAL reference and confirm publication before public return.
Incremental sample receipts retain latency, exclusive measured-call allocation/GC
deltas, head evidence and publication results. Final flush must be empty. Writer
and fresh reader must agree on rows, independent four-field input digest and
expanded event/link digest. Expanded digests must also agree across all four arms.
Any failure aborts without retry. Child deadlines include compilation (300 seconds).

Deterministic gates (no native workload):

```sh
python3 scripts/test-untyped-abba-launcher.py
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
 /home/chuck/ai-src/worktrees/jolt-v0810-durable-wal-private-hint/target/release/jolt \
 -Srepro -Sdeps '{:paths ["src" "bench"] :deps {io.github.chucklehead-dev/jolt-chdb {:local/root "/home/chuck/ai-src/qualification-worktrees/jolt-chdb-9ec4d6b-durable"}}}' \
 -m otel.exporter.scalar-abba-contract-test
```

After authorization, from this committed worktree, launch exactly once:

```sh
python3 scripts/qualify-untyped-abba.py run /home/chuck/ai-src/evidence/untyped-schema-abba-UNIQUE
```

`preflight ROOT` prepares directories and checks resolution without opening a
writer; it consumes that evidence root. Neither deterministic gates nor preflight
establish performance or recovery results. No profile has been run at preparation.
