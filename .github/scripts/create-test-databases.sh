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
for i in $(seq 1 30); do
  if psql -q -d waymark10_test -c 'SELECT 1' >/dev/null 2>&1; then
    echo "postgres reachable as ${PGUSER} after ${i} attempt(s)"
    break
  fi
  if [ "$i" = "30" ]; then
    echo "::error::postgres never accepted a connection as ${PGUSER} on ${PGHOST}:${PGPORT}"
    exit 1
  fi
  sleep 2
done

"$(dirname "$0")/../../scripts/test-databases.sh" \
  | psql -q -d waymark10_test -v ON_ERROR_STOP=1 -f -

echo "test databases present:"
psql -tA -d waymark10_test \
  -c "SELECT datname FROM pg_database WHERE datname LIKE 'waymark10%' ORDER BY 1"
