#!/usr/bin/env bash
# Whether a diff can move the UI walks (ui-access, ui-quest-phone,
# ui-guided; ticket 3ca3e028). Reads the changed paths on stdin, one a
# line, and prints ui=true when one matches a :glob of the list
# (ci/ui-paths.edn unless $1 names another), ui=false when none does.
# An empty list prints ui=true: every walk runs, as before the list.
# tests.yml's `changes` job appends the line to $GITHUB_OUTPUT; why it
# answered goes to stderr, for that job's log.
#
# A glob is a bash [[ == ]] pattern, so * crosses a / as well.
set -euo pipefail

list=${1:-"$(dirname "$0")/../ci/ui-paths.edn"}
globs=()
while IFS= read -r g; do
  globs+=("$g")
done < <(grep -o ':glob[[:space:]]*"[^"]*"' "$list" | sed 's/^:glob[[:space:]]*"//; s/"$//')

if [ "${#globs[@]}" -eq 0 ]; then
  echo "ui-paths: no glob in $list — the walks run" >&2
  echo "ui=true"
  exit 0
fi

while IFS= read -r f; do
  [ -n "$f" ] || continue
  for g in "${globs[@]}"; do
    # shellcheck disable=SC2053 # $g is a pattern on purpose
    if [[ $f == $g ]]; then
      echo "ui-paths: $f matches $g — the walks run" >&2
      echo "ui=true"
      exit 0
    fi
  done
done
echo "ui-paths: no changed path reaches the walks — they are skipped" >&2
echo "ui=false"
