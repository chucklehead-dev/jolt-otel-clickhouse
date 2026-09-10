# Publishing checklist

This repository is intended for
`https://github.com/chucklehead-dev/jolt-otel-clickhouse.git`.

The first publication pins only remotely reachable immutable dependencies:

- `io.github.chucklehead-dev/jolt-chdb` is pinned to
  `58f090caa31445bcf9403a15bdd01b1901a4e860` at
  `https://github.com/chucklehead-dev/jolt-chdb.git`.
- `io.github.casselc/otel` is pinned to the remotely reachable commit
  `fc95971fdb6041cee303717a9dacc4ed04de9b16` at
  `https://github.com/casselc/otel.git`. Its older compatibility-fork crypto
  declaration is excluded.
- `jolt-lang/jolt-crypto` is selected directly from canonical upstream commit
  `5effcc89a3258499a79a2a3d69edad9e7800d1bf` at
  `https://github.com/jolt-lang/jolt-crypto.git`.
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
