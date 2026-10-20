# Scalar normalization cost ceiling

Official stock Jolt0.8.23, published codec6288a7c and SDK14f58e5. Reconstructs
the local Durable screen's 5,000-row rich source shape, including additional
span fields, grouped trace IDs and equal-but-distinct resource/event attribute
roots. This isolates untyped span projection/nested JSON/outer JSON; typed
projection, native ingestion, queueing, WAL and publication are excluded.
Scope attribute roots exist in the source but span-row does not promote them
into these trace columns. There are 15,000 converted maps per invocation.

Six interleaved samples per arm, with complete output byte equality:

| Arm | Median ms | Estimated bytes/row |
|---|---:|---:|
| Existing shape-plan payload converter | 139.49 | 9577.9 |
| Same converter with root-cache insertion budget zero | 125.75 | 9575.7 |
| All normalized maps prepared before timing | 114.19 | 6646.6 |

The frozen arm is an intentionally unrealistic cost ceiling, NOT a cache or
API proposal. It retains all fixture maps/results beyond production bounds,
omits timed admission/conversion and reuses outputs. Even that saves only
25.3ms at these medians, not a factor-of-two pipeline gain. All arms coexist in
one process and share the retained fixture; no claim of production retention or
population-tail qualification follows. Six samples are preliminary evidence.

The no-store result does not prove that cache charging costs 13.7ms. It also
changes hash-table occupancy, root lookup and collection behavior, and is noisy.
Source inspection shows `weight` runs only while insertion count is below its
limit. On this fixture it stops after 64 successful admissions, NOT for all
15,000 maps. If character bounds prevent admission before count fills, other
workloads may keep attempting charge computation. Don't generalize the prior
three-full-walks-per-map description to every invocation.

Next measured slice reuses stock in-range sorted-node accessors after existing
node guards, avoiding four repeated public index checks per node. See sibling
branch `perf/scalar-native-accessors-20261020`; a new representation bridge is
not a prerequisite for this small optimization. Longer-term map/materialization
work remains a candidate, not an implemented or qualified feature.
