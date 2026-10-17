# Bounded typed projection

The experimental native-byte compact span path can collect just the values
needed by an approved attribute manifest. It still visits and normalizes every
input key in the same order. Complete captured attribute dictionaries are not
filtered or discarded: this optimization only changes temporary typed-field
projection work.

This path requires Durable export, typed span descriptors,
`:insert-format :json-compact-each-row` and
`:json-backend :native-guarded-byte-batch`. The ordinary two-argument portable
projector is unchanged. It does not change JSON encoding, SQL/WAL contents,
timestamp choices or persistence acknowledgments.

On the qualified Jolt runtime, built-in maps with at most 128 entries use
invocation-owned storage. Repeated normalized keys remain repeated values, so
typed collision/status rules stay intact. More than eight output groups promote
to a normal builder; larger input maps and unsupported map layouts use the
portable collection fallback. These limits are optimization bounds, not capture
limits. Calls never share mutable scratch or overwrite input nodes.

The focused gate is `jolt -M:native-projection-test`. It checks callback order,
live replacement and reentry, collision buckets, fallback, input ownership,
single-seal positional output and deliberately wrong traversal/field-order
controls. The gate runs in CI with the authenticated qualified Jolt artifact.
