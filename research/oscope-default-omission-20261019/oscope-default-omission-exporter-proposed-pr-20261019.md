## Summary

Advance the root exporter and optional native JSON pins to carry the measured
nested-attribute allocation fix into Oscope:

- exporter `4d48871` -> `4a42716` ([exporter #139](https://github.com/chucklehead-dev/jolt-otel-clickhouse/pull/139));
- optional native JSON `96f3e47` -> `2172061` ([data.json #20](https://github.com/casselc/data.json/pull/20), merged).

Keep the SDK, driver, ordinary JSON, runtime artifact and backend/storage
defaults unchanged. The separately pinned minimal `profiles/embedded` remains
tracked by #167; these root-app tests do not qualify that profile.

## Validation

- Focused root suites: **112 tests / 1,586 assertions**, covering actual
  dependency selection, config, export options, embedded lifecycle and server.
- Real forced-prior exporter and active-native-alias JSON coordinates are
  selected and rejected by the exact current-source oracle. The alias mutation
  uses `override-deps` so `extra-deps` cannot shadow the causal control.
- Three real native streaming fixtures pass in serial fresh writer processes:
  embedded direct SDK, standalone ingestion, and embedded approved typed
  manifest. Every fixture observes native stream calls and nested JSON companion
  execution. Embedded direct SDK and standalone ingestion validate separately
  settled fresh-reader receipts; the typed-manifest fixture checks live typed
  ingestion/query results within its writer process, not independent recovery.
- Source-only Claude review of the exact three-file diff reports no defects;
  Codex checked the dependency literals and scope against the source and tests.

No new throughput, S3 performance, or delivery/timing guarantee is claimed here.
The measured local JSON improvement and broader performance work remain tracked
in exporter #138. Changelog included.
