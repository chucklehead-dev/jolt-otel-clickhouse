## Summary

Pin Oscope's root exporter to the merged singleton-event allocation improvement
from chucklehead-dev/jolt-otel-clickhouse#140 (`2c54b98`). Spans containing one
event avoid three transient builders while preserving conversion order and
the generic/lazy path. This is an allocation improvement, not a throughput claim.

Only three files change: the exporter pin, its exact resolved-root regression
test, and the changelog. SDK7878, driver3d, ordinary JSON0f, optional native
JSON217, runtime/native defaults and `profiles/embedded` are unchanged. This
does not add or enable multi-worker capture.

## Validation

- Root dependency/config/export-options/embedded/server tests: **113 tests,
  1,589 assertions**, no failures or errors. Real dependency resolution selects
  the exact new exporter root. An actual prior-4a coordinate resolves successfully
  but is rejected by the new root check; all older causal controls remain.
- Three serial real-native fixtures pass using the qualified hosted runtime7098
  and explicit libchdb26.9.0: embedded, standalone and typed embedded. Real stream
  calls and the bound JSON companion are exercised. Embedded/standalone retain
  their independent fresh-reader receipts; typed embedded checks live typed
  ingestion/query, not independent typed recovery.
- Source-only Claude review completed with no functional findings; main reviewed
  the diff and owns the executed gates. `git diff --check` passes.

These are root-app gates, not qualification of the separately pinned minimal
embedded profile, live Samizdat/Langfuse, S3 performance or sustained tails.
