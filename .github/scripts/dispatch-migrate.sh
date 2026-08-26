#!/usr/bin/env bash
#
# Dispatch the workqueue10-migrate job on the home cluster and wait for it.
#
#   dispatch-migrate.sh <image_tag>          # dry run: print the plan
#   dispatch-migrate.sh <image_tag> apply    # execute the plan
#
# Needs NOMAD_ADDR and NOMAD_TOKEN in the environment, and the CI token
# must carry waymark-ci-deploy (dispatch-job, read-job, read-logs).
#
# WHY WE READ THE LOGS AND NOT JUST THE ALLOC STATUS. migrate! exits 1
# both when a plan is waiting and when it could not reach Postgres at
# all, and Nomad renders both as a failed alloc. Only the printed line
# tells them apart, so the decision is made on what migrate! SAID:
#
#   "storage matches the declarations — empty plan."  → nothing to do
#   "N migration step(s):"                            → a plan is waiting
#   neither                                           → a real failure
#
# A dry run with a waiting plan is NOT a CI failure. It is the gate.
set -euo pipefail

TAG="${1:?usage: dispatch-migrate.sh <image_tag> [apply]}"
MODE="${2:-dry}"
JOB="workqueue10-migrate"

args=(-detach -meta "image_tag=${TAG}")
[ "$MODE" = "apply" ] && args+=(-meta "apply=1")

echo "dispatching ${JOB} (${MODE}) for image_tag=${TAG}"
out="$(nomad job dispatch "${args[@]}" "$JOB")"
id="$(printf '%s\n' "$out" | awk -F'=' '/Dispatched Job ID/ {gsub(/[[:space:]]/,"",$2); print $2}')"
if [ -z "$id" ]; then
  echo "could not read a dispatched job id from nomad's answer:"
  printf '%s\n' "$out"
  exit 1
fi
echo "dispatched: ${id}"

# Batch allocs are short, but a cold image pull is not. 15 minutes.
status=pending
for _ in $(seq 1 90); do
  status="$(nomad job allocs -json "$id" | jq -r '[.[]] | sort_by(.CreateTime) | last | .ClientStatus // "pending"')"
  case "$status" in complete | failed) break ;; esac
  sleep 10
done

alloc="$(nomad job allocs -json "$id" | jq -r '[.[]] | sort_by(.CreateTime) | last | .ID // empty')"
if [ -z "$alloc" ]; then
  echo "the dispatch never produced an allocation (last status: ${status})"
  exit 1
fi
echo "allocation: ${alloc} (${status})"

# Both streams: the plan rides stdout, a stack trace rides stderr.
logs="$(nomad alloc logs "$alloc" migrate 2>/dev/null || true)"
errs="$(nomad alloc logs -stderr "$alloc" migrate 2>/dev/null || true)"

echo "--- migrate output ---"
printf '%s\n' "$logs"
[ -n "$errs" ] && { echo "--- stderr ---"; printf '%s\n' "$errs"; }
echo "----------------------"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Schema plan (\`${MODE}\`) for \`${TAG}\`"
    echo '```'
    printf '%s\n' "$logs"
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
fi

if printf '%s' "$logs" | grep -q 'empty plan\.'; then
  empty=true
elif printf '%s' "$logs" | grep -qE '[0-9]+ migration step\(s\):'; then
  empty=false
else
  echo "migrate named neither an empty plan nor a step list — this is a failure, not a gate."
  exit 1
fi

if [ "$MODE" = "apply" ]; then
  # migrate! exits 0 only once every step is applied; a skipped
  # destructive step keeps it at 1, and Nomad renders that as failed.
  if [ "$status" != "complete" ]; then
    echo "the apply did not complete (status: ${status})."
    echo "if migrate skipped destructive steps, they are a person's job:"
    echo "  nomad alloc exec -task postgres <alloc> psql -U workqueue -d workqueue10 -c '...'"
    exit 1
  fi
  echo "applied."
  exit 0
fi

echo "plan_empty=${empty}"
[ -n "${GITHUB_OUTPUT:-}" ] && echo "plan_empty=${empty}" >> "$GITHUB_OUTPUT"
exit 0
