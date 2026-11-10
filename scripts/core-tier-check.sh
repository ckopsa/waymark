#!/usr/bin/env bash
# The spine reads no new module kind by name (core tier, slice 1;
# docs/core-tiers.md). The factory's kinds (ticket, change, ci_run,
# repo_policy) and the dwelling's (self, journal, letter) belong to
# their modules; a core namespace asks for what they know through a
# hook (waymark10.modules.hooks), not by reading them.
#
# HOW A READ IS RECOGNISED. One line of a .clj under waymark10/src that
# opens a store call — `(store/<fn> ` — and, later on that same line,
# names one of those kinds as a keyword or a string (`:ticket`,
# `"ticket"`) followed by a space, a quote, `)`, `}` or the line's end.
# Each such line is one read. A call whose kind sits on a later line,
# or is held in a variable, is not seen: the check is a ratchet on the
# plain spelling, not a proof.
#
# Fails when a file's count rises above its entry in the grandfather
# list (ci/core-tier-grandfather.edn unless $1 names another), or when
# a file not on the list has any. A count may go down; lower its entry
# when it does. tests.yml's `changes` job runs it, and appends
# refused=... to $GITHUB_OUTPUT when it fails, for `gate` to print.
set -euo pipefail

cd "$(dirname "$0")/.."
list=${1:-ci/core-tier-grandfather.edn}
read_re='\(store/[a-z!?-]+ .*[:"](ticket|change|ci_run|repo_policy|self|journal|letter)([" )}]|$)'

declare -A recorded=()
while read -r path count; do
  recorded[$path]=$count
done < <(sed -nE 's/^[[:space:]{]*"([^"]+)"[[:space:]]+([0-9]+).*/\1 \2/p' "$list")

refusals=()
while IFS= read -r -d '' f; do
  count=$(grep -cE "$read_re" "$f" || true)
  [ "$count" -gt 0 ] && echo "$count $f"
  if [ -n "${recorded[$f]:-}" ]; then
    if [ "$count" -gt "${recorded[$f]}" ]; then
      refusals+=("$f reads module kinds $count times, past the ${recorded[$f]} $list records for it")
    fi
  elif [ "$count" -gt 0 ]; then
    refusals+=("$f reads module kinds $count times and is not in $list")
  fi
done < <(find waymark10/src -type f -name '*.clj' -print0 | sort -z)

if [ "${#refusals[@]}" -eq 0 ]; then
  echo "core-tier: no spine file reads a module kind past its recorded count" >&2
  exit 0
fi
for r in "${refusals[@]}"; do
  echo "core-tier: FAIL — $r; ask the module through a hook (waymark10.modules.hooks)" >&2
done
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "refused=core-tier: ${refusals[*]}; ask the module through a hook (scripts/core-tier-check.sh)" >>"$GITHUB_OUTPUT"
fi
exit 1
