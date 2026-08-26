#!/usr/bin/env bash
#
# Distil kaocha's profiling output down to the one table a
# duration-weighted split needs: seconds, tests, namespace.
#
# WHY THIS EXISTS RATHER THAN JUST READING THE ARTIFACT. The raw output
# is a few thousand lines, of which the useful part is a dozen. Worse,
# an artifact can only be fetched from a browser or a host that may
# reach *.blob.core.windows.net — an agent sandbox behind an egress
# proxy generally cannot, and this table is exactly what an agent is
# asked to reason about. Printed at the END of the job log it survives
# a `tail`, which every log reader has.
#
# The input is kaocha's own layout, two lines per namespace:
#
#   Top 13 slowest kaocha.type/ns (91.94816 seconds, 99.5% of total time)
#     waymark10.coherence-test
#       8.75690 seconds average (43.78452 seconds / 5 tests)
#
# The TOTAL is what matters, not the average — a split moves whole
# namespaces, so what a shard costs is the sum of their totals.
# Reading stops at the next "Top N slowest" header, which is the
# per-var table: far longer and not what a split can act on.
#
# ANSI is stripped because kaocha bolds the timings and the runner
# keeps the escapes.
set -euo pipefail

src="${1:-}"
if [ -z "$src" ] || [ ! -f "$src" ]; then
  echo "namespace-timings: no output file at '${src}' — nothing to distil" >&2
  exit 0
fi

sed -e 's/\x1b\[[0-9;]*[a-zA-Z]//g' "$src" | awk '
  # Any "Top N slowest" line ends the ns table and starts something else.
  /^Top [0-9]+ slowest/ {
    in_ns = ($0 ~ /kaocha\.type\/ns/)
    next
  }
  !in_ns { next }
  # "  waymark10.coherence-test"
  /^[ \t]+[A-Za-z0-9._-]+[ \t]*$/ {
    ns = $1
    next
  }
  # "    8.75690 seconds average (43.78452 seconds / 5 tests)"
  # POSIX awk only: no capture groups, so cut the field out by hand.
  # mawk is /usr/bin/awk on both the hosted and the self-hosted image.
  /\([0-9.]+ seconds \/ [0-9]+ tests?\)/ {
    if (ns == "") next
    line = $0
    sub(/^.*\(/, "", line)        # -> 43.78452 seconds / 5 tests)
    total = line
    sub(/ .*$/, "", total)        # -> 43.78452
    sub(/^[^\/]*\/ */, "", line)  # -> 5 tests)
    tests = line
    sub(/ .*$/, "", tests)        # -> 5
    printf "%s\t%s\t%s\n", total, tests, ns
    ns = ""
  }
' | sort -rn
