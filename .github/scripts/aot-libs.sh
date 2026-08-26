#!/usr/bin/env bash
#
# Precompile the third-party Clojure libraries into <project>/classes,
# so every test JVM loads them as bytecode instead of compiling them
# from source again.
#
# WHAT THIS IS AND IS NOT. It compiles the LIBRARIES only, never
# waymark10 — see waymark10/src/waymark10/aot.clj for the reasoning:
# the framework's cache key would have to include its own source, and
# CI runs because the source changed, so it would miss every time that
# mattered. The libraries move only when deps.edn moves.
#
# CACHE-KEYED ON deps.edn, and so is the Maven store, which is why
# they share one cache entry in .github/actions/setup-clojure: they
# must rebuild together or the bytecode could outlive the jar it came
# from.
#
# A HIT MAKES THIS A NO-OP. classes/ arrives populated from the cache
# and there is nothing to do. On a miss every job compiles, in
# parallel, which costs about one compile of wall clock — the same
# shape the shards already have.
#
# NEVER FATAL. If the compile fails the classes dir is emptied and the
# suites run from source exactly as they do today: this is an
# optimisation, and it must not be able to turn a green suite red.
set -uo pipefail

project="${1:?usage: aot-libs.sh <project-dir>}"
cd "$(dirname "$0")/../.." || exit 1
cd "$project" || exit 1

if [ -d classes ] && [ -n "$(ls -A classes 2>/dev/null)" ]; then
  echo "aot: ${project}/classes restored from cache ($(find classes -name '*.class' | wc -l) classes), skipping compile"
  exit 0
fi

mkdir -p classes
start=$(date +%s)
# -X, not -M: it ignores :main-opts, so this cannot collide with the
# :test alias's kaocha.runner entry point. :test is present for its
# extra-deps — malli.generator needs test.check, and kaocha is the
# runner every shard loads.
if clojure -X:test:aot waymark10.aot/compile-libs; then
  echo "aot: ${project} compiled $(find classes -name '*.class' | wc -l) classes in $(( $(date +%s) - start ))s"
else
  echo "::warning::aot: compile failed for ${project}; falling back to loading from source"
  rm -rf classes
  mkdir -p classes
fi
exit 0
