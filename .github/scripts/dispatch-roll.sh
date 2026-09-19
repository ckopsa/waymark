#!/usr/bin/env bash
#
# Deploy workqueue10 with no downtime: dispatch the workqueue10-roll job
# on the home cluster and wait for its verdict.
#
#   dispatch-roll.sh <image_tag>
#
# Needs NOMAD_ADDR and NOMAD_TOKEN in the environment, and the CI token
# must carry waymark-ci-deploy (dispatch-job, read-job, read-logs) — the
# same grant dispatch-migrate.sh spends, and nothing more.
#
# WHAT THE ROLL JOB DOES (home-infrastructure, nomad-jobs/workqueue10-roll.hcl).
# It registers a new VERSION of workqueue10 on this tag, which walks the
# job's canary roll: the new allocation starts beside the old one, must
# pass /healthz, and only then takes over. It waits for that deployment
# and then writes image_tag to nomad/jobs/workqueue10/deploy itself.
#
#   exit 0  the canary was promoted; the new image is serving
#   exit 1  it never turned healthy; Nomad auto-reverted, the OLD image
#           never stopped serving, and the variable still names it
#
# This script does not write that variable, and nothing else should:
# terraform renders the job's tag from it, so a hand-written value moves
# what terraform believes without moving what runs.
#
# CI cannot register jobs (submit-job is namespace-wide, so it is not in
# the CI token). It can only ask the cluster to run the roll the infra
# repo defines, with one argument, which the roll job checks is a sha.
set -euo pipefail

TAG="${1:?usage: dispatch-roll.sh <image_tag>}"
JOB="workqueue10-roll"

echo "dispatching ${JOB} for image_tag=${TAG}"
out="$(nomad job dispatch -detach -meta "image_tag=${TAG}" "$JOB")"
id="$(printf '%s\n' "$out" | awk -F'=' '/Dispatched Job ID/ {gsub(/[[:space:]]/,"",$2); print $2}')"
if [ -z "$id" ]; then
  echo "could not read a dispatched job id from nomad's answer:"
  printf '%s\n' "$out"
  exit 1
fi
echo "dispatched: ${id}"

# A roll is a cold image pull, a JVM boot, 15 s of health, and the old
# task's 25 s shutdown delay; a FAILED roll waits out the 5-minute
# healthy deadline first. 20 minutes covers both.
#
# The logs are read WHILE THE ALLOC LIVES and the largest capture kept,
# for dispatch-migrate.sh's reason: a dead batch alloc is collected
# within seconds on a busy node. (The roll job also holds itself open
# for 10 s after its verdict, for exactly this reader.)
status=pending
alloc=""
logdir="$(mktemp -d)"; trap 'rm -rf "$logdir"' EXIT
: > "$logdir/out.log"; : > "$logdir/err.log"
best_out=0; best_err=0
shown=0
for _ in $(seq 1 600); do
  if [ -z "$alloc" ]; then
    alloc="$(nomad job allocs -json "$id" | jq -r '[.[]] | sort_by(.CreateTime) | last | .ID // empty')"
  fi
  if [ -n "$alloc" ]; then
    if nomad alloc logs "$alloc" roll > "$logdir/out.tmp" 2>/dev/null; then
      sz=$(wc -c < "$logdir/out.tmp")
      [ "$sz" -gt "$best_out" ] && { mv "$logdir/out.tmp" "$logdir/out.log"; best_out=$sz; }
    fi
    if nomad alloc logs -stderr "$alloc" roll > "$logdir/err.tmp" 2>/dev/null; then
      sz=$(wc -c < "$logdir/err.tmp")
      [ "$sz" -gt "$best_err" ] && { mv "$logdir/err.tmp" "$logdir/err.log"; best_err=$sz; }
    fi
    # Print the roll's progress as it arrives, so a watcher sees the
    # deployment move instead of a silent wait.
    lines=$(wc -l < "$logdir/out.log")
    if [ "$lines" -gt "$shown" ]; then
      tail -n +"$((shown + 1))" "$logdir/out.log"
      shown=$lines
    fi
  fi
  status="$(nomad job allocs -json "$id" | jq -r '[.[]] | sort_by(.CreateTime) | last | .ClientStatus // "pending"')"
  case "$status" in complete | failed) break ;; esac
  sleep 2
done

if [ -z "$alloc" ]; then
  echo "the dispatch never produced an allocation (last status: ${status})"
  exit 1
fi
echo "allocation: ${alloc} (${status})"
if [ -s "$logdir/err.log" ]; then
  echo "--- the roll's stderr ---"
  cat "$logdir/err.log"
fi

case "$status" in
  complete)
    echo "deployed ${TAG} with no restart in place."
    ;;
  failed)
    echo "THE ROLL FAILED; the reason is above. The previous image is still serving: a tag the roll job refused was never registered, and a canary that never turned healthy was auto-reverted."
    exit 1
    ;;
  *)
    echo "gave up waiting after 20 minutes; the roll may still be running (status: ${status})."
    echo "check: nomad job status workqueue10"
    exit 1
    ;;
esac
