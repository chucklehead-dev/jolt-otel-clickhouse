## Summary

Add an explicit, default-off `:experimental-span-scalar-views?` path using the
separate SDK-owned encoder handoff. Repeated sorted attribute shapes currently
build normalized HAMTs and numeric Strings immediately before encoding. A
trusted codec view avoids those intermediates while retaining original policy,
shape/budget checks and the same encoded values.

The existing map APIs stay map-returning. Missing capabilities reject before
storage, and changed/custom policy retains the ordinary fallback. The feature
requires the existing explicit codec, payload-cache and shape-plan options.
No publication, acknowledgement, capture or default-feature behavior changes.

Select merged consumer SDK `6cd57c8` from [SDK PR68](https://github.com/casselc/otel/pull/68)
and published codec `68ee1ca`; users do not need local-root overrides. Driver,
crypto and configured JSON pins are unchanged. This does not merge the older
SDK native-helper lineage into SDK main or repin Oscope.

## Validation

- Independent library source + Linux standalone **43 / 596**. Red/green lookup
  control: old output constructs1pmap+5numeric Strings for17 fields, view0/0;
  identical bytes, bounds/depth, failure order, output independence and live
  policy changes tested.
- Published exporter integration **3 / 19**, rejecting missing support before
  JDBC and explicitly rejecting nil/false as a fake view-selection witness.
- Existing published regression **71 / 553**, inferred shapes **4 / 15**, and
  actual dependency-resolution controls **61** pass on stock Jolt0.8.23/Chez10.4.1.
- Published-stack mixed-signal native writer +fresh reader pass in both raw-tick
  and auto/ISO modes. Dynamic event values exercise distinct sorted roots; full
  physical rows and owned-span/configured-log+metric routing checked.
- Earlier public release app independently recovered **350,000 local-Durable
  rows** with the same implementation via local-root SDK selection. Allocation
  fell about8.9%, but this sequential pair does **not** establish a throughput or
  tail win. Rich component allocation fell13.6%; its126ms GC outlier remains in
  the results. No S3/live-app/population-tail or target-throughput claim.
  [Evidence](https://github.com/chucklehead-dev/jolt-otel-clickhouse/tree/a373c1b4a1c02c6972e64bb05fc7333836e82347/research/scalar-view-exporter-20261020).
- Codec, SDK and exporter implementation Claude read-only reviews approved.
  SDK PR68's two exact-head checks passed and merge parents/tree were verified.
  Exporter implementation68ee1ca's stock-library CI passed. Final pin/native-CI
  follow-up is reviewed separately; all final-head PR checks must pass before merge.

Changelog and user configuration docs included. No new concurrency/model state;
unchanged exhaustive model checks were not rerun locally. Completed temporary
stores/builds were retired after receipt validation; only small evidence remains.
