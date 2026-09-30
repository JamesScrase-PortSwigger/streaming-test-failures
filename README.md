# Streaming test failures prototype

Can a Gradle build show each test failure on GitHub while the tests are still
running, without extra check runs or a PR comment?

The `streaming-test-failures` plugin (`build-logic/`) attaches a
`TestListener` to every `Test` task. Each failure goes to one of two sinks:

| mode | how | known limits |
|---|---|---|
| `check-run` | Adds annotations to the job's own check run (`job.check_run_id`) using `PATCH /check-runs/{id}`, batched every `STREAMING_FAILURES_FLUSH_SECONDS`. Title shows "N test failures so far". | Needs `checks: write`. Each PATCH counts against the `GITHUB_TOKEN` rate limit. |
| `workflow-command` | Prints `::error file=…,line=…,title=…::message` as each test fails. | The runner caps these at 10 error annotations per step. |

`auto` (the default) uses `check-run` when `CHECK_RUN_ID`, `GITHUB_TOKEN` and
`GITHUB_REPOSITORY` are all set, `workflow-command` on any other Actions run,
and `off` locally.

## The experiment

`.github/workflows/streaming-failures-experiment.yml` runs on push:

- **Stream via check run API** / **Stream via workflow commands** run the same
  slow suite (18 cases, 10 s each, two in three fail, plus two quick failures),
  one mode per job.
- **Observe annotations while tests run** polls both jobs' check runs every 10 s.
  It writes a timeline to its job summary: job status, annotation count and
  check run title at each poll.
- **Inspect check runs after completion** dumps each check run's final title,
  summary and annotations.

What to read from the run:

1. Does the annotation count rise while `status` is `in_progress`? If so,
   failures stream, for each mode.
2. Does `PATCH` on the Actions-owned check run return 200 with `GITHUB_TOKEN`?
   The job log prints `[streaming-failures] PATCH … -> <status>`.
3. Once the job completes, has the runner overwritten the plugin's title/summary,
   or dropped its annotations?
4. Workflow-command mode: are there 14 failure annotations, or only 10?
5. Where do the annotations show up in the UI? Check the PR checks list, the
   job page and the Files changed tab (push to a branch and open a PR for that).

Run locally against a stub:

```bash
STREAMING_FAILURES_MODE=check-run CHECK_RUN_ID=1 GITHUB_TOKEN=x GITHUB_REPOSITORY=o/r \
  GITHUB_API_URL=http://127.0.0.1:18765 ./gradlew --console=plain test -Pdemo.cases=6 -Pdemo.delayMillis=1500
```

## Open questions before using this for real

- **Configuration cache.** The listener holds a `Provider<BuildService>` plus
  plain `File`s. Not yet checked with the configuration cache on.
- **Rate limits.** Many parallel matrix cells each updating their check run is
  fine only because the plugin sends a PATCH only when there are new failures.
  Worst case per cell: one PATCH per flush interval, plus one per 50 failures.
- **Jobs that run tests inside a container.** `CHECK_RUN_ID` and `GITHUB_TOKEN`
  have to be passed through into the container.
