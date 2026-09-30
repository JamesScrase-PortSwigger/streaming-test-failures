#!/usr/bin/env bash
# Polls the named jobs' check runs every POLL_SECONDS and records, per poll, the job
# status next to the check run's output title and annotation count. An annotation
# count above zero while status is in_progress is the evidence that failures stream.
set -euo pipefail

: "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}"
POLL_SECONDS="${POLL_SECONDS:-10}"
MAX_POLLS="${MAX_POLLS:-120}"
TIMELINE="$(mktemp)"

job_json() {
  gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/jobs?per_page=100" \
    --jq ".jobs[] | select(.name == \"$1\")"
}

printf '%s\t%s\t%s\t%s\t%s\t%s\n' time job step status annotations title | tee -a "$TIMELINE"

for ((poll = 1; poll <= MAX_POLLS; poll++)); do
  all_done=true
  for name in "$@"; do
    job="$(job_json "$name")"
    if [[ -z "$job" ]]; then
      all_done=false
      continue
    fi
    id="$(jq -r .id <<<"$job")"
    status="$(jq -r .status <<<"$job")"
    step="$(jq -r '[.steps[]? | select(.status == "in_progress") | .name] | first // "-"' <<<"$job")"
    check="$(gh api "repos/$GITHUB_REPOSITORY/check-runs/$id")"
    annotations="$(jq -r '.output.annotations_count' <<<"$check")"
    title="$(jq -r '.output.title // "-"' <<<"$check")"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$name" "$step" "$status" "$annotations" "$title" | tee -a "$TIMELINE"
    [[ "$status" == "completed" ]] || all_done=false
  done
  $all_done && break
  sleep "$POLL_SECONDS"
done

{
  echo "## Annotation counts seen while the jobs ran"
  echo
  echo '| time | job | running step | status | annotations | check run title |'
  echo '|---|---|---|---|---|---|'
  tail -n +2 "$TIMELINE" | awk -F'\t' '{printf "| %s | %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5, $6}'
} >> "$GITHUB_STEP_SUMMARY"
