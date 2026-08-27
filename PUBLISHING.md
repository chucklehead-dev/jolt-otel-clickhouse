# Publishing checklist

This repository is intended for
`https://github.com/chucklehead-dev/jolt-otel-clickhouse.git`.

The first publication pins only remotely reachable immutable dependencies:

- `io.github.chucklehead-dev/jolt-chdb` is pinned to
  `d7f1c2b684f185459d313c934835c16282e8e42c` at
  `https://github.com/chucklehead-dev/jolt-chdb.git`.
- `io.github.casselc/otel` is pinned to the remotely reachable commit
  `70187410f8877307606a98c4bd105c41075acb0e` at
  `https://github.com/casselc/otel.git`.

`org.clojure/data.json` is already pinned to the remotely reachable
`casselc/data.json` commit
`8a6dc9668e5c3596a335759defeb7ec80cd3b5f8`.

The release gate is:

1. verify that `deps.edn` contains no `:local/root` entries;
2. install the chDB and Hegel native libraries using the commands in CI;
3. run `jolt -Srepro -M:test` on Jolt v0.7.27;
4. verify the same workflow from a clean clone; and
5. tag only the exact green commit and publish its full SHA for consumers.
