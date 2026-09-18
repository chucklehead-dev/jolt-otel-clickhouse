# Publishing checklist

This repository is intended for
`https://github.com/chucklehead-dev/jolt-otel-clickhouse.git`.

The first publication pins only remotely reachable immutable dependencies:

- `io.github.chucklehead-dev/jolt-chdb` is pinned to
  `58f090caa31445bcf9403a15bdd01b1901a4e860` at
  `https://github.com/chucklehead-dev/jolt-chdb.git`.
- `io.github.casselc/otel` is pinned to the remotely reachable commit
  `87d3ac1a9b26ec6c0bf0c44d3b5aff4c66ccb5a0` at
  `https://github.com/casselc/otel.git`. Its older compatibility-fork crypto
  declaration is excluded.
- `jolt-lang/jolt-crypto` is selected directly from canonical upstream commit
  `5effcc89a3258499a79a2a3d69edad9e7800d1bf` at
  `https://github.com/jolt-lang/jolt-crypto.git`.
- `metosin/malli` 0.20.1 defines the public typed-registry data schemas and is
  exercised by the pinned-Jolt aggregate gate.
- The test alias pins `io.github.chucklehead-dev/jolt-hegel` to
  `b214f769983211431c74e427f0f35553cfba7b34`.

`org.clojure/data.json` is already pinned to the remotely reachable
`casselc/data.json` commit
`97298fd8a67a6d4ee3eb1346d5e184beb9565b90`.

The release gate is:

1. verify that `deps.edn` contains no `:local/root` entries;
2. install the chDB and Hegel native libraries using the commands in CI;
3. run `jolt -Srepro -M:test` on Jolt v0.8.3;
4. verify the same workflow from a clean clone; and
5. tag only the exact green commit and publish its full SHA for consumers.
