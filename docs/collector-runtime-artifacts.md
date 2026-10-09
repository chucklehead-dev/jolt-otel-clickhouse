# Collector runtime artifacts

The experimental collector stack uses a specific plain Jolt build. It is not
the aspect compiler, a released Jolt compatibility promise, or proof that an
application has passed its own tests.

`scripts/verify-collector-runtime-manifest.py` checks the prepared
`sorted-collector-7c` manifest contract. It requires the exact compiler source
and tree, Chez version, platform, CI scope, ranged append, vector allocation and
sorted-dispatch capability claims, including their rejected old-body controls.
It rejects missing, duplicate or contradictory fields and malformed provenance.
Failures print a fixed marker, not manifest content or exception details.

Run its offline controls with:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-collector-runtime-manifest.py
```

These tests use synthetic manifests and provenance. They do not download an
artifact or run a real compiler. The fixture's required claims are independent
of the verifier's policy, so removing a requirement cannot silently weaken both
the implementation and its positive test.

## What this does not authorize

The verifier does **not** authenticate GitHub metadata, ZIP bytes or the binary,
execute the binary, select a new downloader profile, or expose AWS credentials.
The existing downloader and CI runtime selections are unchanged. The new
producer still needs review and a successful hosted run before it has real
run/artifact identifiers and independent digests. A local binary checksum is
not a substitute.

Before integrating this verifier into a credential-bearing consumer:

1. Review the exact producer workflow and supporting compiler/library changes.
2. Authenticate a completed successful canonical producer run and attempt,
   repository, event, branch and reviewed workflow SHA.
3. Pin its artifact identity, association and ZIP digest independently; reject
   expired artifacts or missing permissions without choosing another compiler.
4. Verify exact ZIP members, binary digest and `SHA256SUMS`, then check this
   manifest against the independently selected provenance.
5. Run the real standalone capability checks and matching local export/recovery
   workload before assuming the S3 CI role.
6. Qualify the actual S3 workload and fresh recovery. Keep performance and
   correctness claims separate and remove successful run-specific stores.

Only after those receipts exist should the closed downloader profile and CI
pins be updated. There is no `latest` or arbitrary caller-selected compiler
fallback. S3 failures must not fall back to local storage. Logs and retained
artifacts must not include credentials, headers, telemetry or database objects.
