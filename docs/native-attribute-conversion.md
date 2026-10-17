# Bounded attribute conversion

With explicit `:json-backend :native-guarded-byte-batch`, the exporter can
convert built-in attribute maps without allocating temporary entries and
key/value pairs for every field. Other backends keep the existing conversion.
The current shortcut for reusable string-only maps still takes precedence.

Every key and value converter runs in the same input sequence order, including
live replacements and nested conversion calls. Normalized-key collisions keep
the existing winner. The complete captured dictionaries, JSON encoding and
Durable acknowledgment rules are unchanged.

Maps of at most 128 entries use invocation-owned construction storage. A
collision-free string-key hash map may copy its tree shape; only unchanged
immutable leaf pairs can be shared. Mutable node arrays are always private.
If a converter changes a key, already converted fields move into a normal
builder without calling converters again. Collision buckets use the ordered
generic walk. Larger maps and unsupported layouts keep portable conversion;
the optimization bound is not a limit on captured fields.

This is a qualified Jolt runtime path. Required callable helpers are checked
for presence/arity when the factory loads, before opening database storage.
The known map/hash-node/collision-node nongenerative IDs and field layouts
are checked too; an unexpected layout fails before storage. Runtime operation
semantics remain a qualified boundary, not a universal ABI proof. The exporter
does not add public
receiver-classifier calls to the current attribute conversion path.

Run `jolt -M:native-attributes-test` with the qualified runtime. The gate checks
wire order, collision precedence, metadata, input ownership, shared immutable
leaves, live callbacks, reentry, exceptions and portable fallback. Its controls
include a wrong traversal, an added observable classifier and a missing runtime
helper. The missing-helper control also proves a closure without the capability
guard could otherwise be constructed successfully.
