# Hosted stock-library result

- Run: https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39354256503
- Exact source head: `f9ed3bdf6820d9d66c6f7e63e66cac6f800298e5`.
- Job: `stock-library`, ID `118405308608`, completed **success**.
- Inspected actual job logs, not only workflow metadata: official archive and
  executable SHA256 checks both report OK; source-mode traversal runs **5 / 42**,
  source-mode codec/cache **14 / 144**, and the newly built standalone executable
  **19 / 186**, all without failures/errors.
- The job explicitly reports compilation and creation of `codec-smoke` before
  executing it. This is not merely a source-mode test labeled as standalone.

This independently confirms the library's default Linux standalone build on
stock Jolt 0.8.23. It does not qualify the exporter preparation refactor, native
Durable throughput, other build modes/platforms or Oscope packaging. This
evidence-only follow-up changes no gate/runtime source and does not require
rerunning unchanged checks.
