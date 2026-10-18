The JSON loop review is reconciled: narrowed independent review found no
ordering/publication inconsistency under the existing default-options guard;
main checked the actual code and retained the separate callback/cleanup gates.

Consumer integration is published as stacked branch
[5a7617a...20999f9](https://github.com/chucklehead-dev/jolt-otel-clickhouse/compare/5a7617a4b3c806f21e15b902993156fc2f751a3c...20999f9c37d01ce1629f9ae2033bd12483030fc7).
Native JSON alias only; ordinary JSON/SDK/driver unchanged. Actual SDK-ended
event test2/12, wider native attr/projection47/940, resolved dependency56checks
pass locally. Source review found no functional issue; retained the earlier
old-SHA rejection control as requested. Exact-head CI39136349818 dispatched;
not yet claimed green. No PR/merge.

Two useful attribution results:

- Transient into3 plus sorted JSON saves another~8.3KB/row, but has no clear
  time benefit:9/16pairs faster, mean paired ratio~1.008. Keep as an allocation
  and upstream correctness candidate, not a demonstrated throughput fix.
- Actual SDK2k phase screen: row preparation128.5ms vs outer encoding35.2ms
  (full175.2ms); preparation43.4KB/row vs encoding3.65KB/row. Equal complete
  bytes verified; component times are not additive/end-to-end tail evidence.

**Correction:** source inspection rules out the earlier pdata-raw hypothesis
for this fixture. span-row serializes events directly; pdata-raw is used for
structured attribute values, not these scalar event maps. Next attribution
targets are attribute shaping and nested-event JSON production. No protocol
transition/model change or unchanged exhaustive run is needed for this slice.
