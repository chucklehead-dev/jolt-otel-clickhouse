# Sorted JSON consumer / cumulative attribution checkpoint

Previous turn: genuine progress, implemented JSON a18, CI green, actual Durable
paired gain with independent full physical recovery. This turn: consumer pin,
tests, independent reviews and cumulative/phase measurements.

## Current source

JSON published topic perf/sorted-map-byte-seq-20261018, a18ae06c787e6e03709bd78f1b92911130a613f1.
Exporter new stacked topic perf/sorted-json-consumer-20261018,
HEAD20999f9c37d01ce1629f9ae2033bd12483030fc7,
base5a7617a4b3c806f21e15b902993156fc2f751a3c.
Worktree /home/chuck/ai-src/worktrees/exporter-sorted-json-consumer-20261018.
Only native JSON alias changes; ordinary0f, SDK697 and driver3d unchanged.
User-review/submission boundaries remain; NO PR opened/merged or external
upstream mutation. Existing SDK697/exporter5a topics still await submission
decision. Proposed text exporter-sorted-json-consumer-proposed-pr-20261018.md.

## Verification

- New actual-ended SDK event test2/12 PASS85015 terminal0. First invocation
  was blocked by sandbox GitHub resolution and is NOT credited; qualified run
  resolved the published JSONa18 source rather than using a local root.
- Native attributes/projection +new test47/940 PASS50316 terminal0.
- Dependency55checks PASS51121 terminal0; retained old-old native SHA negative
  check at reviewer suggestion, final56checks PASS16026 terminal0. Delayed
  process exit was observed via same handle; no duplicate or termination.
- Published exact20999f9. Push does NOT trigger this repo's workflow (main/PR
  only); explicitly dispatched existing tests.yml, CI39136349818. It was
  confirmed in_progress at20999f9, native-json-backends/scoped-s3-sdk/test.
  Refresh exact result before qualification/public action.

## Independent reviews

- Narrow JSON loop review76780 TERMINAL0: no defect within included default
  admission, public sequence effects, comma/key/next ordering and publication
  scope. Main independently compared actual raw key/value reads before name
  conversion; reviewer wording about child order was imprecise but identified
  no functional mismatch. Existing full native/exception tests remain separate
  evidence, not claimed as reviewed architecture/proof. Prior full packet
  timeout remains uncredited.
- Exporter review17546 TERMINAL0: no functional pin/CI/test defect. Suggested
  retaining old-old SHA negative control; added three lines as20999f9 and ran
  final dependency gate. Rejected assertion that a pin's performance benefit
  must be reimplemented in its consumer diff: reviewed source, same-byte
  measurements and actual recovery already exist; no timing claim is inferred
  from consumer equivalence tests. Initial pin/test commit revieweda5e4eb0,
  followup only restores the reviewer-requested test (no production change).

## New performance findings

Jolt latest release refreshed via upstream release API: v0.8.23, published
2026-10-17T19:41:22Z. Current native runs still frozen1ae/Chez10.4.1, no compiler
rebuild/migration or default replacement. Latest converters.ss also retains
Boolean-through-printer path; inspecting rt.ss shows booleans already return
literal strings without allocation, so no speculative Boolean rewrite begun.

Four-arm same-byte actual SDK-produced2k,16rotating rounds:

| full encoding | p50ms | managedB/row |
|---|---:|---:|
| baseline |318.312|68861|
| sorted JSON |216.236|46970|
| transient into3 source overlay |285.598|60570|
| combined |209.736|38681|

Combined vs sorted JSON9/16pairs faster, mean pairedratio1.0076. CPU totals
3.720s->3.697s across16fullsamples, GC real~60ms each. No convincing incremental
speed win; allocation saves~8289B/row (~17.6%). Not throughput or tail evidence.
Source overlay restored in finally, no artifact/global compiler modification.

CORRECTION to earlier hypothesis: span-row serializes events directly via
nested-json-string, NOT pdata-raw. That function is used for structured
attribute value conversion; it is not an event-map reconstruction hotspot in
this scalar fixture. Do not spend next slice optimizing the wrong call graph.

Phase attribution on JSONa18, actual SDK-produced2k/16samples:

| phase | p50ms | managedB/row |
|---|---:|---:|
| prepare rows |128.515|43371|
| encode prepared |35.175|3653|
| full |175.188|46967|

Preparation uses collector-equivalent dynamic byte writer +empty-link companion;
complete prepared/full bytes equal before/after. Prebuilt rows retained in all
phases; timing totals not additive. First attempt77852 terminal1 used a Delay
as IFn in diagnostic harness; fixed force, qualified45937 terminal0. Keep both
receipts, no failed run credited. Next target is attribute shaping and nested
event JSON production, not outer byte-emitter microtuning or pdata-raw.

All local work and reviews terminal0 except explicitly recorded failed probes.
No generated native store/build/download created in this turn. Source/scalar
artifacts only. Need refresh hosted CI; do not rerun unchanged exhaustives.
