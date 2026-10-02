#!/usr/bin/env bash
#
# Run one JVM command of a suite, and run it one more time when the JVM
# itself died and the tests never spoke.
#
#   jvm-retry.sh <label> <dir> <output-file> <command> [args...]
#
# The command runs inside <dir>; its output goes to the log and to
# <output-file> (read from the caller's directory when relative), so a
# failing suite still leaves its timings behind. The exit code is the
# command's own: the last attempt's.
#
# WHEN IT RETRIES. The shell's 134 (SIGABRT, what an hs_err crash ends
# in) or 139 (SIGSEGV), and no verdict in the output. Kaocha exits with
# its failure count, so 134 alone could be 134 failing tests: a summary
# line or a FAIL/ERROR report in the output is a verdict, and it
# stands. Unanchored, because kaocha colours those lines. Safe to
# repeat on the same database: every namespace opens by dropping its
# own tables. PR #768 lost its gate to one compiler-thread crash in
# test10's shard 4 with no assertion failed.
#
# WHY ONE SCRIPT. test10's shard step held this alone at first, and
# every other job that starts a JVM the same way still failed the gate
# on one crash. Every suite's step in .github/workflows/tests.yml calls
# this now.
#
# A retry writes retried=true to the step's outputs; the job's `JVM
# crash report` step reads it, because the first attempt's hs_err file
# is still on disk and a green job is no reason to lose it.
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "usage: $0 <label> <dir> <output-file> <command> [args...]" >&2
  exit 2
fi
label="$1"
dir="$2"
out="$3"
shift 3
case "$out" in
  /*) ;;
  *) out="$PWD/$out" ;;
esac

# tee, with pipefail keeping the command's exit code rather than tee's.
attempt() {
  (cd "$dir" && "$@" 2>&1 | tee "$out")
}

rc=0
attempt "$@" || rc=$?
if { [ "$rc" = 134 ] || [ "$rc" = 139 ]; } \
   && ! grep -Eq '[0-9]+ tests, [0-9]+ assertions|(FAIL|ERROR) in ' "$out"; then
  echo "::warning::$label: the JVM aborted (exit $rc) and no test failure was reported — retrying one time"
  echo "retried=true" >> "${GITHUB_OUTPUT:-/dev/null}"
  rc=0
  attempt "$@" || rc=$?
  echo "$label: the retry after the JVM abort exited $rc"
fi
exit "$rc"
