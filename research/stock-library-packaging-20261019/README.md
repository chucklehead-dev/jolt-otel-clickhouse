# Stock-Jolt standalone library packaging

This follow-up to the stock-runtime traversal/codec split changes only the
packaging test, project paths, CI and documentation. It does not modify the
traversal/encoding kernels or claim a Durable performance improvement.

## Local evidence

Official Linux x86_64 Jolt 0.8.23, SHA256
`7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`,
through the required Chez 10.4.1 wrapper:

- A separate traversal-only standalone binary: **5 tests / 42 assertions**, green.
  Its entrypoint checks the JSON namespace is absent before and after testing.
- Preliminary combined standalone gate: **19 / 186**, green.
- Final checked-in packaging project and entrypoint: **19 / 186**, green.
- Running that final binary from `/tmp`, under `strace -f -e trace=openat`,
  again passes **19 / 186**. The recorded trace contains no library source-root,
  traversal/JSON Scheme resource or output `.build` directory opens. This
  checks that the observed run did not silently fall back to those files;
  it does not prove all filesystem behavior or all platforms/build options.

The unprivileged strace attempt was denied by the sandbox's ptrace policy;
the escalated diagnostic completed successfully. Neither attempt is a
performance measurement. `openat` trace lines contain local file paths, not
captured telemetry or credentials; no network/data I/O was traced.

## Launcher workaround

With the official binary, `-Sdeps ... build` fails with unbound
`jolt.host/build-binary`; putting `build` first loads that driver, but its
argument handling does not provide the earlier inline test classpath.
The tiny `incubator/codec/packaging/deps.edn` supplies relative library/test
paths and no dependencies, allowing `build` first. No compiler patch or
compiler-source build is required. Failed launcher attempts are not counted
as library test failures.

CI retains its existing isolated source-mode traversal and codec checks, then
builds and executes the combined standalone entrypoint. The existing validated
temporary directory and EXIT trap cover the executable and its `.build`
directory. Local success is not evidence that the new hosted job has finished.

## Review and custody

Claude read-only review completed without a reported defect. Codex independently
ran the actual checked-in packaging command, inspected the file-open diagnostic,
and checked the diff. Binary hashes and terminal logs are retained, not the
executables or large compiler intermediates. All three synthetic standalone
outputs and their intermediate directories were retired after terminal builds
and runs, with logs copied before cleanup (see `retired-kib.txt`).

Remaining boundaries: this is default Linux standalone library testing, not
full exporter/SDK/Oscope packaging, Windows/macOS, shared-library deployment,
closed-world/direct-link builds, runtime-layout ABI stability or throughput.
