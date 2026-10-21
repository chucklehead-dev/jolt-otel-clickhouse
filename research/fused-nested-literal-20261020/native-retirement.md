# Native comparison retention receipts

## Current cached arm

Exact fresh root `/tmp/fused-native-cached.tkDkf1t8`, allocated747640KiB.
Writer90058 terminal0, selected scope35 times; 175000 actual physical rows.
First reader14478 failed before recovery because its explicit nil-native-reader
guard Var is absent in selected JSON0f51. This failure log is preserved.
Corrected independent reader27120 selects JSON3adc8c5, preserves that guard
and every physical-field/multiplicity assertion, terminal0. The writer was
not rerun and no fake Var or weakened guard was added.

Validator returned0: 32 confirmed 5k-row measured callbacks +3 warmups, full
old-stack recovery with 35 copies per record, physical types and exact nanos.
Writer/reader receipts copied to native-results and cmp verified before
retiring only this synthetic root. No other source/cache/data path selected.

## First fused+cached arm

Exact fresh root `/tmp/fused-native-fused.eZUyoNtm`, allocated752180KiB.
Writer84726 and independent reader97726 terminal0;35 scope selections,
175000 full physical rows. Validator0 and a wrong-arm negative control
correctly rejects with :label :arm (exit1), rather than accepting any receipt.
Writer/reader receipts copied/compared before this exact synthetic root's
retirement. Reader uses the same explicit JSON3adc pin and unmodified oracle.
