# Latest-runtime prerequisite audit: corrected by main-agent source inspection

The adjacent Claude source audit (session86324terminal0) is advisory and its
minimal-port plan is rejected. Do not implement its proposed one-helper port.

Direct inspection of exactv0.8.23source finds no definitions or references for:

- `pmap-fold-seq-order` incollections.ss;
- `make-protocol-method-site` inprotocols.ss;
- `na-owned-bv->bytearray` injava/natives-array.ss;
- `sb-set!` injava/string-builder.ss.

JSON96f directly calls all four. Its byte writer additionally requires the
three-argument observable-classification boundary form of the method site.
The first/classification capability also depends on coherent registry epochs,
domain/owner proofs and callback behavior. Porting only an old simple resolver
would not preserve the qualified semantics. Generic helpers are separable from
this larger method-site/callback capability.

`load-writer!` is a delay dereference, not a catch-and-decline wrapper around
missing Scheme helpers. Ordinary configured JSON can still work; explicitly
activating the optional native writer does not silently decline this absence.

The measured row preparation is Clojure `otel.exporter.chdb/span-row`, its
conversion helpers and typed projector. It is not solely the SDK's raw Scheme
converters. Thus the audit's claim that runtime direct-linking cannot apply to
the measured preparation path is unfounded.

Source18dce94a, based on stablev0.8.23a0ac71c3, confirms
`hc-compiler-direct-linking?` -> `jolt-direct-link-emit` incompile-eval.ss.
The public opt-in is `*compiler-options* {:direct-linking true}` while loading
code. It may freeze inlined callees; live callback/policy root behavior must
be measured, not assumed. No native helper port, default install change,
canonicalintegration/aspects edit or production direct-link opt-in occurred.

The accompanying fresh-process configured-JSON screen uses actual exporter
source on latest-release source runtime, without claiming equivalence to the
qualified native writer or Durable pipeline. Initial exploratory open-arm run
29993had a driver edit during startup and is excluded; the hash-guarded runner
uses new filenames and a frozen final source in every arm.
