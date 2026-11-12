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
# Fails when any file has one: the check is absolute (core tier, slice
# 5). The grandfather list it began with is gone; every read it counted
# now asks a hook. tests.yml's `changes` job runs it, and appends
# refused=... to $GITHUB_OUTPUT when it fails, for `gate` to print.
set -euo pipefail

cd "$(dirname "$0")/.."
read_re='\(store/[a-z!?-]+ .*[:"](ticket|change|ci_run|repo_policy|self|journal|letter)([" )}]|$)'

refusals=()
while IFS= read -r -d '' f; do
  count=$(grep -cE "$read_re" "$f" || true)
  if [ "$count" -gt 0 ]; then
    echo "$count $f"
    refusals+=("$f reads module kinds $count times")
  fi
done < <(find waymark10/src -type f -name '*.clj' -print0 | sort -z)

if [ "${#refusals[@]}" -eq 0 ]; then
  echo "core-tier: no spine file reads a module kind" >&2
  exit 0
fi
for r in "${refusals[@]}"; do
  echo "core-tier: FAIL — $r; ask the module through a hook (waymark10.modules.hooks)" >&2
done
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "refused=core-tier: ${refusals[*]}; ask the module through a hook (scripts/core-tier-check.sh)" >>"$GITHUB_OUTPUT"
fi
exit 1
