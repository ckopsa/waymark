#!/usr/bin/env bash
# Carve aliases can only go down (ticket 600d0875). A carve that moves
# code from a parent namespace into its sub-directory can leave an alias
# in the parent so no caller had to change:
#   (def x sub/x)   or   (def ^:private x @#'sub/x)
# An alias here is a top-level def of that shape, in a .clj under the
# four src trees, whose target namespace lives under the file's own
# sub-directory (seats.clj -> seats/**.clj), named by its :as alias or
# in full. Prints each file's count, and fails when one is past the
# count ci/carve-alias-grandfather.edn (unless $1 names another)
# records for it, or when a file not on the list has any. A count may
# go down; lower its entry when it does.
#
# Not counted, by a trailing comment on the alias's own line:
#   ; api              a public name the parent offers on purpose, which
#                      happens to be implemented in a sub-namespace. A
#                      @#' alias reaches into a private and is never api:
#                      it is counted however it is marked.
#   ; test: <test ns>  a var that test namespace redefines or derefs.
# tests.yml's `changes` job runs it, and appends refused=... to
# $GITHUB_OUTPUT when it fails, for `gate` to print.
set -euo pipefail

cd "$(dirname "$0")/.."
list=${1:-ci/carve-alias-grandfather.edn}
roots=(waymark10/src factory10/src workqueue10/src calendar10/src)

declare -A recorded=()
while read -r path count; do
  recorded[$path]=$count
done < <(sed -nE 's/^[[:space:]{]*"([^"]+)"[[:space:]]+([0-9]+).*/\1 \2/p' "$list")

# 1 the ^:private, 2 the @#', 3 the alias or namespace, 4 what follows
alias_re="^\\(def[[:space:]]+(\\^:private[[:space:]]+)?[^[:space:]()]+[[:space:]]+(@#')?([^[:space:]()/]+)/[^[:space:]()]+\\)(.*)\$"
api_re='^[[:space:]]*;+[[:space:]]*api[[:space:]]*$'
test_re='^[[:space:]]*;+[[:space:]]*test:[[:space:]]*[^[:space:]]+'

# a file's namespace: root/a/b_c.clj -> a.b-c
ns_of() {
  local p=${2#"$1"/}
  p=${p%.clj}
  p=${p//\//.}
  echo "${p//_/-}"
}

refusals=()
for root in "${roots[@]}"; do
  while IFS= read -r -d '' f; do
    d=${f%.clj}
    rec=${recorded[$f]:-0}
    if [ ! -d "$d" ]; then
      continue
    fi
    # the names this file may write a sub-namespace by: each one's full
    # name, and the alias its ns form gives it
    declare -A names=()
    while IFS= read -r -d '' s; do
      names[$(ns_of "$root" "$s")]=1
    done < <(find "$d" -type f -name '*.clj' -print0)
    while read -r ns as; do
      if [ -n "${names[$ns]:-}" ]; then
        names[$as]=1
      fi
    done < <(grep -oE '\[[^][:space:]]+[[:space:]]+:as[[:space:]]+[^][:space:]()]+' "$f" |
               sed -E 's/^\[([^[:space:]]+)[[:space:]]+:as[[:space:]]+(.*)$/\1 \2/')
    count=0
    while IFS= read -r line; do
      if ! [[ $line =~ $alias_re ]]; then
        continue
      fi
      # each later =~ resets BASH_REMATCH: keep the groups first
      reach=${BASH_REMATCH[2]:-}
      target=${BASH_REMATCH[3]:-}
      rest=${BASH_REMATCH[4]:-}
      if [ -z "${names[$target]:-}" ]; then
        continue
      fi
      if [[ $rest =~ $test_re ]]; then
        continue
      fi
      if [ -z "$reach" ] && [[ $rest =~ $api_re ]]; then
        continue
      fi
      count=$((count + 1))
    done < <(grep -E '^\(def[[:space:]]' "$f")
    unset names
    if [ "$count" -gt 0 ]; then
      echo "$count $f"
    fi
    if [ "$count" -gt "$rec" ]; then
      if [ -n "${recorded[$f]:-}" ]; then
        refusals+=("$f has $count carve aliases, past the $rec $list records for it")
      else
        refusals+=("$f has $count carve aliases and $list records none for it")
      fi
    fi
  done < <(find "$root" -type f -name '*.clj' -print0 | sort -z)
done

if [ "${#refusals[@]}" -eq 0 ]; then
  echo "carve-alias: no file past its recorded count of carve aliases" >&2
  exit 0
fi
for r in "${refusals[@]}"; do
  echo "carve-alias: FAIL — $r; call the sub-namespace, or end a public name's alias with ; api" >&2
done
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "refused=carve-alias: ${refusals[*]}; call the sub-namespace, or mark a public name ; api (scripts/carve-alias-check.sh)" >>"$GITHUB_OUTPUT"
fi
exit 1
