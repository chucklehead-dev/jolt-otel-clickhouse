# Publishing checklist

This repository is intended for
`https://github.com/chucklehead-dev/jolt-otel-clickhouse.git`.

The first publication pins only remotely reachable immutable dependencies:

- `io.github.chucklehead-dev/jolt-chdb` is pinned to
  `824115821a53e4d7da544b31dbe920fef292493f` at
  `https://github.com/chucklehead-dev/jolt-chdb.git`.
- `io.github.casselc/otel` is pinned to the remotely reachable commit
  `ebcb0d1b36532155452d7a75842e232f7feeb548` at
  `https://github.com/casselc/otel.git`.
- The test alias pins `io.github.chucklehead-dev/jolt-hegel` to
  `b214f769983211431c74e427f0f35553cfba7b34`.

`org.clojure/data.json` is already pinned to the remotely reachable
`casselc/data.json` commit
`8a6dc9668e5c3596a335759defeb7ec80cd3b5f8`.

The release gate is:

1. verify that `deps.edn` contains no `:local/root` entries;
2. install the chDB and Hegel native libraries using the commands in CI;
3. run `jolt -Srepro -M:test` on Jolt v0.8.0;
4. verify the same workflow from a clean clone; and
5. tag only the exact green commit and publish its full SHA for consumers.
