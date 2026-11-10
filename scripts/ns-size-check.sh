#!/usr/bin/env bash
# No server namespace grows past a size ceiling (ticket fc62e248): a
# seat that opens a 500 KB file to change one function stalls on
# context. Lists every .clj under the four src trees with its byte
# size, and fails when one is over the ceiling (100 KB) and not in the
# grandfather list (ci/ns-size-grandfather.edn unless $1 names
# another), or when a grandfathered one grows past the size recorded
# there. A grandfathered file may shrink; lower its entry when it does.
# tests.yml's `changes` job runs it, and appends refused=... to
# $GITHUB_OUTPUT when it fails, for `gate` to print.
set -euo pipefail

cd "$(dirname "$0")/.."
list=${1:-ci/ns-size-grandfather.edn}
ceiling=102400

declare -A recorded=()
while read -r path size; do
  recorded[$path]=$size
done < <(sed -nE 's/^[[:space:]{]*"([^"]+)"[[:space:]]+([0-9]+).*/\1 \2/p' "$list")

refusals=()
while IFS= read -r -d '' f; do
  size=$(($(wc -c <"$f")))
  echo "$size $f"
  if [ -n "${recorded[$f]:-}" ]; then
    if [ "$size" -gt "${recorded[$f]}" ]; then
      refusals+=("$f is $size bytes, past the ${recorded[$f]} $list records for it")
    fi
  elif [ "$size" -gt "$ceiling" ]; then
    refusals+=("$f is $size bytes, past the $ceiling-byte ceiling")
  fi
done < <(find waymark10/src factory10/src workqueue10/src calendar10/src -type f -name '*.clj' -print0 | sort -z)

if [ "${#refusals[@]}" -eq 0 ]; then
  echo "ns-size: no namespace past the ceiling or its recorded size" >&2
  exit 0
fi
for r in "${refusals[@]}"; do
  echo "ns-size: FAIL — $r; split the namespace" >&2
done
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "refused=ns-size: ${refusals[*]}; split the namespace (scripts/ns-size-check.sh)" >>"$GITHUB_OUTPUT"
fi
exit 1
