#!/usr/bin/env bash
#
# The databases the waymark10 suites need, as SQL on stdout.
#
#   scripts/test-databases.sh | psql -h host -p port -U user -d waymark10_test -f -
#
# WHY THIS EXISTS. `make db10` created waymark10_test and stopped, but
# the suites reach for EIGHT databases: the batch-B, batch-D, grants,
# intents, meal-plan, presence and UI tests each open their own, on
# purpose — a suite that drops tables by name must not share a database
# with one that does the same (waymark10.test.db/with-test-engine).
#
# On a machine that has run these suites for a while the extra seven
# already exist, created by hand once and kept alive by the docker
# volume. So the gap was invisible: the suite passed locally and could
# not pass anywhere else. CI found it on its first run, with
#   FATAL: database "waymark10_d_test" does not exist
#
# One list, two callers — `make db10` and .github/workflows/tests.yml —
# so a database added to a new test cannot be added to only one of them.
#
# Idempotent: \gexec runs only the CREATE statements the SELECT emits,
# and it emits none for a database that already exists. CREATE DATABASE
# cannot run inside a transaction or a DO block, which is why this is
# shaped as generated SQL rather than a procedure.
set -euo pipefail

# Every database named by a jdbc: URL under */test. Keep alphabetical.
DATABASES=(
  waymark10_b_test        # batch_b_members_test, batch_b_access_test
  waymark10_d_test        # batch_d_relay_test, batch_d_collab_test, collab_ticket_test
  waymark10_grants_test   # batch_b_mint_test
  waymark10_intents_test  # intents_test
  waymark10_mp_test       # coherence_test
  waymark10_presence_test # presence_test
  waymark10_test          # the default WAYMARK10_TEST_DSN target
  waymark10_ui_test       # batch_a_dev
)

if [ "${1:-}" = "--list" ]; then
  printf '%s\n' "${DATABASES[@]}"
  exit 0
fi

printf 'SELECT '\''CREATE DATABASE '\'' || quote_ident(d)\n'
printf 'FROM unnest(ARRAY[%s]) AS d\n' \
  "$(printf "'%s'," "${DATABASES[@]}" | sed 's/,$//')"
printf 'WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = d)\n'
printf '\\gexec\n'
