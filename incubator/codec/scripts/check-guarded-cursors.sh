#!/usr/bin/env bash
set -euo pipefail
codec_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$codec_root"
command -v z3 >/dev/null
sha256sum --check formal/smt/guarded-cursors/source-sha256.txt
python3 - <<'PY'
from pathlib import Path
import subprocess

source = Path('formal/smt/guarded-cursors/cursor-advance.smt2').read_text()
cases = {
    'advance': (source, 'unsat'),
    'input-guard-mutant': (source.replace(
        '(assert (! input_guard_enabled :named current_input_guard))',
        '(assert (! (not input_guard_enabled) :named omitted_input_guard))') +
        '\n(assert (= kind 0))\n(assert (= i machine_max))\n(assert (= n machine_max))\n'
        '(assert (= cursor 0))\n(assert (= capacity 1))\n', 'sat'),
    'output-guard-mutant': (source.replace(
        '(assert (! (=> (not (= kind 2)) (< cursor capacity)) :named output_body_guard))',
        '(assert (! true :named omitted_output_guard))') +
        '\n(assert (= kind 1))\n(assert (= n 0))\n(assert (= i 0))\n'
        '(assert (= capacity 1))\n(assert (= cursor 1))\n', 'sat'),
    'small-control': (source + '\n(assert (= kind 0))\n(assert (= i 0))\n'
        '(assert (= n 1))\n(assert (= cursor 0))\n(assert (= capacity 1))\n', 'unsat'),
    'inclusive-boundary': (source.replace(
        '(assert (! violation :named counterexample))',
        '(assert (! (not violation) :named valid_boundary))') +
        '\n(assert (= kind 0))\n(assert (= i (- machine_max 1)))\n'
        '(assert (= n machine_max))\n(assert (= capacity 65536))\n'
        '(assert (= cursor 65535))\n', 'sat'),
}
for name, (query, expected) in cases.items():
    result = subprocess.run(['z3', '-in', '-smt2'], input=query + '\n(check-sat)\n',
                            text=True, capture_output=True, check=True)
    actual = result.stdout.strip()
    if actual != expected:
        raise SystemExit(f'FAIL {name}: expected {expected}, got {actual}')
    print(f'OK {name}: {actual}')
PY
