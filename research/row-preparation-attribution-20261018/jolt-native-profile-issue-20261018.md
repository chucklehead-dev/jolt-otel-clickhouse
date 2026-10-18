## Problem

The available GNU gprofng 2.42 collector completed a bounded Jolt/Chez encoding
probe successfully, but its samples do not qualify as a CPU hotspot profile.
The report explicitly warns:

> Collection interval timer period was changed (2000 -> 0); profile data may be unreliable

It records a 3.0378-second active collection window but only 0.062 seconds of CPU
samples. 0.058 seconds (93.55% of that small captured set) are `<Unknown>`.
These are two separate issues: timer interference/coverage and generated-code
symbol resolution. Successful exit/byte parity does not fix either issue.

## Bounded reproducer

- Qualified Jolt `1ae7e0d19aa3088789410106610beb171bcfb0ef`, artifact
  06a2ccb858c072bfcd74ce94d96eb95f008d75e0fb1f46117728602572189811.
- Mandatory Chez 10.4.1, WSL2 kernel 6.6.87.2, x86_64.
- Exporter `20999f9`, SDK `697f58b`, JSON `a18ae06`; no DB operations or external service.
- 2,000 real SDK-produced ended spans with 17 scalar attributes/resource/event
  fields and one event/span; warmup completed before collection.
- 20 encoding iterations (40,000 rows), exact output size/parity checked.
- `gprofng collect app -a off -p 2 -y USR2 ...`; collector_resume/collector_pause
  void(void) API starts/stops only around the encoding loop. No startup samples
  intentionally included. Target ran with sanitized HOME/PATH/LANG environment,
  no credentials or real telemetry. No binary archiving.
- Authoritative report says timer changed; do not infer that all time is in
  mutexes/GC based on the few named samples, or that 93% of whole runtime CPU is
  unknown. It is 93% of a poorly covered capture.

Small source/scalar reports are in the local research checkpoint; the invalid
516 KB raw experiment was removed after terminal exit 0 and text extraction. No user
compiler/default installation changed.

## Next useful work

1. Identify the timer interaction before using gprofng time percentages.
2. Evaluate a timer-independent sampler and a JIT symbol/unwind bridge for Chez
   generated code, or document exact limits if unavailable.
3. Distinguish Chez source/block **execution counts** from sampled CPU time.
   Enabling compile-profile instruments/recompiles code; it is not a drop-in
   CPU profile of the existing frozen artifact. See the
   [Chez profiler documentation](https://cisco.github.io/ChezScheme/csug10.0/system.html).
4. Keep existing coarse timing/allocation and callback/count controls; do not
   weaken correctness gates or rebuild/migrate the aspects compiler solely to
   make an unsupported profiler appear to work. Chez 10.4.1 remains pinned.
