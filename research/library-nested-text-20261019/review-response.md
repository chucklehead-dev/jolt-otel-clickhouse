# Review and CI closure

- Initial source review found no escaping, ownership or byte-limit defect, but
  requested exercising the nested producer/consumer during startup. Added a
  fixed quoted/control/Unicode probe before the backend becomes available,
  plus a rejected raw-splice mutant. Stock local focused suite:13tests/95assertions.
- Follow-up source review accepted that fix. Its assertion about whether a
  failed delay retries is not a verified runtime result and is not relied on.
  The supported contract is fail-before-storage, not delay retry behavior.
- Earlier traversal review's collision gap is addressed by the hcoll UID/field
  fence and a string-hash collision bucket order test. Different admission of
  flat and HAMT maps in the clone optimization is intentional: clone declines
  shapes it cannot reuse safely; the existing generic folder handles them.
- The earlier review text's statement that reported tests did not run clean
  is not an observed result: it ran no commands. Codex's recorded default-path
  regression was50tests/950assertions, zero failures/errors.
- Hosted stock-library CI succeeded at exact production checkpoint
  `5329adc589ebd741d64901aac8f609786bf0fd29`:
  https://github.com/chucklehead-dev/jolt-otel-clickhouse/actions/runs/39323480978
  Codex inspected the log: official archive/binary checksum checks passed;
 8tests/73assertions, zero failures/errors, actually executed. This is library
  CI only, not full native consumer/AOT/S3/Oscope qualification.
- Actual four-arm Durable comparison measured the earlier captured candidate
  patch, before the startup-only probe. All700k rows passed independent
  recovery; no exact final-source full-Durable rerun is claimed.
- No PR/default adoption. The7% allocation improvement is reproducible within
  this research comparison; the3.3% pooled rate difference is smaller than
  control drift, so it is not a qualified throughput win.
