#!/usr/bin/env bash
#
# Feed scripts/test-databases.sh into the postgres service container.
#
# The list itself is NOT here — it lives in scripts/test-databases.sh,
# shared with `make db10`, so a database a new test needs cannot be
# added to the laptop and forgotten in CI. This file is only the plumbing
# for the service container: find psql, wait for the port, pipe.
#
# The service's healthcheck already gates the step, but it runs
# `pg_isready` as root and the image has no `root` role, so it reports
# ready on a connection it could not itself make. The wait below uses
# the real user, which is the one the tests will use.
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5433}"
PGUSER="${PGUSER:-ckopsa}"
export PGHOST PGPORT PGUSER

if ! command -v psql >/dev/null 2>&1; then
  echo "installing postgresql-client"
  sudo apt-get update -qq
  sudo apt-get install -y -qq postgresql-client
fi

# waymark10_test is created by the service's POSTGRES_DB, so it is the
# one database guaranteed to exist to connect through.
# 0.5s, not 2s: the wait is a race against a container that is almost
# ready, and a 2s granularity rounded every job up by an average of a
# second for nothing.
for i in $(seq 1 120); do
  if psql -q -d waymark10_test -c 'SELECT 1' >/dev/null 2>&1; then
    echo "postgres reachable as ${PGUSER} after ${i} attempt(s)"
    break
  fi
  if [ "$i" = "120" ]; then
    echo "::error::postgres never accepted a connection as ${PGUSER} on ${PGHOST}:${PGPORT}"
    exit 1
  fi
  sleep 0.5
done

# ── DURABILITY OFF, BEFORE ANY DATABASE IS MADE ──────────────────────
#
# This is a throwaway database that lives for one job, so an fsync buys
# nothing and costs everything. Measured on the self-hosted ARM node,
# where storage is the real bottleneck rather than the CPU:
#
#   checkpoint complete: wrote 7190 buffers (43.9%); write=269.377 s,
#                        sync=11.203 s, total=280.844 s; sync files=6887
#
# 280 seconds for one checkpoint, and a second at 116s in the same run.
# The suites drop and recreate tables in every namespace, so the write
# volume is enormous and every commit was waiting on the disk.
#
# All three are sighup- or user-context, so ALTER SYSTEM plus a reload
# changes them live. That matters: GitHub Actions service containers
# take no `command`, so postgres cannot be started with -c flags and
# this is the only way in short of building a custom image.
#
# NEVER copy these to a database whose contents matter — with fsync off
# a crash leaves an unrecoverable cluster. That is the correct trade
# here and nowhere near production.
psql -q -d waymark10_test -v ON_ERROR_STOP=1 \
  -c "ALTER SYSTEM SET fsync = off" \
  -c "ALTER SYSTEM SET synchronous_commit = off" \
  -c "ALTER SYSTEM SET full_page_writes = off" \
  -c "SELECT pg_reload_conf()" >/dev/null
echo "durability disabled (throwaway database):"
psql -tA -d waymark10_test \
  -c "SELECT name || '=' || setting FROM pg_settings
      WHERE name IN ('fsync','synchronous_commit','full_page_writes')
      ORDER BY name" | sed 's/^/  /'

"$(dirname "$0")/../../scripts/test-databases.sh" \
  | psql -q -d waymark10_test -v ON_ERROR_STOP=1 -f -

echo "test databases present:"
psql -tA -d waymark10_test \
  -c "SELECT datname FROM pg_database WHERE datname LIKE 'waymark10%' ORDER BY 1"
