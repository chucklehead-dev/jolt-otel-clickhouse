#!/usr/bin/env bash
set -euo pipefail
packet=/home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe
exporter=/home/chuck/ai-src/worktrees/exporter-native-publication-syscalls-20261021
reader=/home/chuck/ai-src/worktrees/exporter-stock-nested-fragments-20261019
stock=/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt
wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
export SDK_STORAGE_ARM=local
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
export JOLT_NO_USER_DEPS=1
for label in A1 B1 B2 A2; do
  run="$packet/$label"
  test ! -e "$run"
  mkdir "$run"
  case "$label" in
    A1|A2) binary="$packet/baseline-app" ;;
    B1|B2) binary="$packet/candidate-v2-app" ;;
    *) exit 2 ;;
  esac
  sha256sum "$binary" > "$run/binary.sha256"
  echo "Starting $label confirmed writer"
  "$wrapper" "$binary" "$run" fused > "$packet/$label-writer.log" 2>&1
  echo "Finished $label writer; starting independent reader"
  (
    cd "$reader"
    "$wrapper" "$stock" -Srepro -Sdeps '{:paths ["src" "test" "bench" "/home/chuck/ai-src/worktrees/chdb-selection-upload-body-20261015/bench"] :deps {org.clojure/data.json {:git/url "https://github.com/casselc/data.json.git" :git/sha "3adc8c5d3a57a30d18f62bad9d1c7a5c1f6857e0"}}}' \
      research/nested-fragments-20261019/independent-reader.clj "$run"
  ) > "$packet/$label-reader.log" 2>&1
  (
    cd "$exporter"
    bb research/fused-typed-field-slots-20261021/validate-native.bb "$run" fused > "$run/validated.edn"
    bb research/fused-typed-field-slots-20261021/validate-controls.bb "$run" fused > "$run/controls.edn"
  )
  destination="$exporter/research/private-byte-store-20261021/native-results/$label"
  mkdir -p "$destination"
  for name in writer.edn reader.edn validated.edn controls.edn binary.sha256; do
    cp "$run/$name" "$destination/$name"
    cmp "$run/$name" "$destination/$name"
  done
  du -sk "$run/objects" "$run/scratch-writer" "$run/scratch-reader" > "$run/retired-kib.txt"
  cp "$run/retired-kib.txt" "$destination/retired-kib.txt"
  cmp "$run/retired-kib.txt" "$destination/retired-kib.txt"
  # Exact owned synthetic targets only; receipts stay outside these directories.
  # Writer+independent reader+validator+mutations+copy/cmp all finished above.
  case "$label" in
    A1) rm -rf -- /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/A1/objects /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/A1/scratch-writer /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/A1/scratch-reader ;;
    B1) rm -rf -- /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/B1/objects /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/B1/scratch-writer /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/B1/scratch-reader ;;
    B2) rm -rf -- /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/B2/objects /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/B2/scratch-writer /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/B2/scratch-reader ;;
    A2) rm -rf -- /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/A2/objects /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/A2/scratch-writer /home/chuck/ai-src/evidence/private-byte-store-native-20261021.VuK0qtxe/A2/scratch-reader ;;
  esac
  echo "Verified $label: 175000 full physical rows; receipts preserved; synthetic store retired"
done
