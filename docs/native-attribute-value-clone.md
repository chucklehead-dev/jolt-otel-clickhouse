# Native attribute conversion: copy values without rebuilding the index

The experimental native-byte backend can avoid rebuilding a wide attribute
map's hash index when all its keys stay unchanged. This is an internal
optimization; it does not change attribute capture, typed fields, JSON escaping,
Durable acknowledgement, or the default backend.

The fast path accepts only built-in hash maps with 9–128 entries, string keys,
and no full-hash collision buckets. It checks that shape before calling any
converter. Every key and value converter is still read live and called once,
in the same sequence order as the existing converter. Values are never cached.

If a converted key is the same string object as its original key, the result
can keep the original hash-tree geometry with fresh private nodes and entries.
No source node is edited or reused as mutable output. Metadata and cached
collection hashes are not copied; ordinary result class/order/hash are retained.

If any converter changes a key, the operation switches immediately to the
existing empty-map builder. Earlier converted pairs are inserted in their
original order without calling their converters again. The changed key is
inserted before the next converter is called, preserving observable hashing
and equality effects for unusual converted keys. Normalized-key collisions use
the same winner and resulting map layout as the existing path. The source's
unchanged string-key hashing/equality is the runtime's guarded pure fast path;
arbitrary mutation of private Scheme runtime helpers is outside this contract.

Small maps, larger maps, non-string input keys, collision buckets, sorted maps,
and other unsupported layouts retain their existing route. Converter errors,
reentrant calls, and live converter changes remain observable. This is not a
cross-runtime ABI promise or a new public customization surface.

Tests cover positive native admission, input ownership, class/order/metadata
and wire parity, changed keys, normalization collisions, live Var replacement,
reentry, exceptions, size boundaries, mixed keys and full-hash collisions.
Performance claims must be based on complete connected export/recovery runs,
not on the row-construction component alone.
