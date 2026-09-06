# Publishing checklist

This repository is intended for
`https://github.com/chucklehead-dev/jolt-otel-clickhouse.git`.

The first publication pins only remotely reachable immutable dependencies:

- `io.github.chucklehead-dev/jolt-chdb` is pinned to
  `a06b2af8df67e6b917b3242818ab933239e5b525` at
  `https://github.com/chucklehead-dev/jolt-chdb.git`.
- `io.github.casselc/otel` is pinned to the remotely reachable commit
  `b18830ae1fc6eef6add5e059ae302e94a1f0ed70` at
  `https://github.com/casselc/otel.git`.
- The test alias pins `io.github.chucklehead-dev/jolt-hegel` to
  `b214f769983211431c74e427f0f35553cfba7b34`.

`org.clojure/data.json` is already pinned to the remotely reachable
`casselc/data.json` commit
`932444043c0c06f9e295ba4963419b2481e9dd07`.

The release gate is:

1. verify that `deps.edn` contains no `:local/root` entries;
2. install the chDB and Hegel native libraries using the commands in CI;
3. run `jolt -Srepro -M:test` on Jolt v0.8.3;
4. verify the same workflow from a clean clone; and
5. tag only the exact green commit and publish its full SHA for consumers.
