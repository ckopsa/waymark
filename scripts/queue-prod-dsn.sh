#!/usr/bin/env bash
# workqueue10 production's JDBC DSN, resolved live (waymark-yqs).
#
#   scripts/queue-prod-dsn.sh                 # print the DSN
#   make migrate-queue-prod                   # what actually uses it
#
# Why a script and not a constant: the nomad alloc publishes Postgres
# on a DYNAMIC port (192.168.1.40:2xxxx -> 5432) that moves every time
# the allocation restarts, so the address has to be asked for rather
# than remembered.
#
# Why it exists at all: `make migrate-queue` plans against
# workqueue10_dev, which is a PROXY for production and answers the
# wrong question in both of its states — stale, it reports columns
# prod already has; current, it reports nothing. The deploy gate asks
# what PROD needs, and only prod can answer that.
#
# This prints a DSN containing the database password. It goes to
# stdout for $(command substitution), so don't paste the output into
# anything that keeps scrollback. Nothing here writes: composing a DSN
# is a read, and the one caller runs the migration planner in its
# dry-run default.
set -euo pipefail

JOB="${JOB:-workqueue10}"
DB_NAME="${DB_NAME:-workqueue10}"
DB_USER="${DB_USER:-workqueue}"
INFRA_SECRETS="${INFRA_SECRETS:-$HOME/dev/home-infrastructure/terraform/secrets.local.json}"

die() { echo "queue-prod-dsn: $*" >&2; exit 1; }

command -v nomad >/dev/null 2>&1 || die "nomad is not on PATH."
command -v jq    >/dev/null 2>&1 || die "jq is not on PATH."
[ -r "$INFRA_SECRETS" ] || die "cannot read $INFRA_SECRETS (set INFRA_SECRETS)."

: "${NOMAD_ADDR:=$(jq -r '.nomad_address // empty' "$INFRA_SECRETS")}"
: "${NOMAD_TOKEN:=$(jq -r '.nomad_token // empty' "$INFRA_SECRETS")}"
export NOMAD_ADDR NOMAD_TOKEN
[ -n "$NOMAD_ADDR" ] || die "no nomad_address in $INFRA_SECRETS."

password="$(jq -r '.workqueue_db_password // empty' "$INFRA_SECRETS")"
[ -n "$password" ] || die "no workqueue_db_password in $INFRA_SECRETS."

# the running allocation — a job mid-roll can briefly show more than
# one, and the newest is the one serving.
#
# The three ways this fails are three different problems, and saying
# the wrong one is the whole reason this script exists: "cannot reach
# nomad" when the job name is simply wrong sends you debugging the
# network. So keep nomad's own words and branch on them.
errf="$(mktemp)"
trap 'rm -f "$errf"' EXIT
if ! allocs="$(nomad job allocs -json "$JOB" 2>"$errf")"; then
  nomad_said="$(tr '\n' ' ' <"$errf" | cut -c1-200)"
  case "$nomad_said" in
    *"No job"*|*"not found"*)
      die "nomad has no job '$JOB' (it answered fine — the name is the problem)." ;;
    *)
      die "could not reach nomad at $NOMAD_ADDR: ${nomad_said:-no error text}" ;;
  esac
fi

alloc="$(printf '%s' "$allocs" \
  | jq -r '[.[] | select(.ClientStatus=="running")]
           | sort_by(.CreateTime) | last | .ID // empty')"
[ -n "$alloc" ] || die "job '$JOB' has no RUNNING allocation. If a deploy is in flight, wait — workqueue10 takes 90s-3min to boot."

addr="$(nomad alloc status -json "$alloc" 2>/dev/null \
  | jq -r '.AllocatedResources.Shared.Ports[]?
           | select(.Label=="db")
           | "\(.HostIP):\(.Value)"' | head -1)"
[ -n "$addr" ] || die "allocation $alloc publishes no 'db' port — has the job spec changed?"

host="${addr%%:*}"
port="${addr##*:}"

# The alloc's db address is a LAN address, so this works from home and
# nowhere else. Say that plainly rather than letting the JDBC driver
# time out into something unreadable two minutes from now.
if ! timeout 5 bash -c "exec 3<>/dev/tcp/$host/$port" 2>/dev/null; then
  die "cannot reach $addr — prod Postgres is only published on the LAN,
                 so this needs to run from home (or over the tunnel).
                 Nomad answered fine, so the cluster itself is up."
fi

printf 'jdbc:postgresql://%s/%s?user=%s&password=%s\n' \
  "$addr" "$DB_NAME" "$DB_USER" "$password"
