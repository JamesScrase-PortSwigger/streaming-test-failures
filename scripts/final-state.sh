#!/usr/bin/env bash
# After the jobs finish: did the check run keep the title/summary the plugin wrote,
# and how many annotations survived (per level)?
set -euo pipefail

: "${GITHUB_REPOSITORY:?}" "${GITHUB_RUN_ID:?}"

{
  echo "## Check runs after completion"
  for name in "$@"; do
    id="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID/jobs?per_page=100" \
      --jq ".jobs[] | select(.name == \"$name\") | .id")"
    check="$(gh api "repos/$GITHUB_REPOSITORY/check-runs/$id")"
    annotations="$(gh api --paginate "repos/$GITHUB_REPOSITORY/check-runs/$id/annotations?per_page=100" --jq '.[]' | jq -s .)"

    echo
    echo "### $name (check run $id)"
    echo
    echo "- conclusion: \`$(jq -r .conclusion <<<"$check")\`"
    echo "- check suite: \`$(jq -r .check_suite.id <<<"$check")\`"
    echo "- output.title: \`$(jq -r '.output.title // "null"' <<<"$check")\`"
    echo "- output.annotations_count: \`$(jq -r .output.annotations_count <<<"$check")\`"
    echo "- annotations returned: \`$(jq length <<<"$annotations")\` — by level: \`$(jq -c 'group_by(.annotation_level) | map({(.[0].annotation_level): length}) | add // {}' <<<"$annotations")\`"
    echo
    echo "<details><summary>output.summary</summary>"
    echo
    jq -r '.output.summary // "(null)"' <<<"$check"
    echo
    echo "</details>"
    echo
    echo "<details><summary>annotations</summary>"
    echo
    echo '| level | path:line | title |'
    echo '|---|---|---|'
    jq -r '.[] | "| \(.annotation_level) | \(.path):\(.start_line) | \(.title // "" | gsub("\\|"; "\\\\|")) |"' <<<"$annotations"
    echo
    echo "</details>"
  done
} | tee -a "$GITHUB_STEP_SUMMARY"
