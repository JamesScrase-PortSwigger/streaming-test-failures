# Streaming test failures prototype

Can a Gradle build show each test failure on GitHub while the tests are still
running, without extra check runs or a PR comment? Yes: the plugin adds
annotations to the **job's own check run**, which it finds with
`${{ job.check_run_id }}`, using `PATCH /check-runs/{id}` while the job runs.

## What the plugin does

`streaming-test-failures` (`build-logic/`) attaches a `TestListener` to every
`Test` task. Every `STREAMING_FAILURES_FLUSH_SECONDS` (default 15) it sends one
PATCH, and only if something has changed:

- **Annotations** for failures that are new since the last PATCH. They point at
  the failing line, taken from the stack trace.
- **Title and summary** showing the current state, e.g.
  `2 failed · 2 may be retried · 3 flaky (still running)`. The summary lists the
  tests under *Failed*, *Failed, may be retried* and *Passed on retry (flaky)*.

It publishes only when `CHECK_RUN_ID`, `GITHUB_TOKEN` and `GITHUB_REPOSITORY`
are all set (mode `auto`, the default), or when you force
`STREAMING_FAILURES_MODE=check-run`.

## Retries (org.gradle.test-retry)

Check-run annotations can only be added. Nothing deletes or edits one. So a
failure that a retry might still fix goes out as a `warning`, and only a test
that is really failing gets a `failure`:

| event | annotation | title/summary |
|---|---|---|
| First failed attempt, retries left | `warning`: "failed, may be retried" | counted as *may be retried* |
| Attempt number `maxRetries + 1` fails | `failure`: "failed all N attempts" (or "failed its last attempt (M of N attempts failed)") | counted as *failed* |
| Still failing when its task finishes (retry filter skipped it, `maxFailures` exceeded, …) | `failure` | counted as *failed* |
| Passes after failing | none (the earlier warning stays) | counted as *flaky* |

**Parameterised and dynamic tests.** test-retry re-runs *every* invocation of
a method that had any failing invocation, so each invocation can flake in its
own way (pass, fail, pass). The plugin therefore:

- matches attempts on Gradle's stable test name (`method(int)[1]`), not the
  display name, which can differ between attempts (`Named.of(... + nanoTime)`)
  or repeat across methods (`[1] 1`, `case 1`);
- counts every execution of an invocation, passes included, as an attempt. An
  invocation that passes, then fails, then fails again reaches its last attempt
  on the third run, with 2 of its 3 attempts failed;
- titles annotations `Class > method(int) > [1] 1`, as Gradle's console does.

The plugin reads `maxRetries` from the task's `retry {}` extension. Retries show
up as further `afterTest` events for the same class and test name, and a task
completion listener (`BuildEventsListenerRegistry.onTaskCompletion`) settles
anything still waiting on a retry.

## Findings from earlier runs

- `GITHUB_TOKEN` can PATCH the job's own check run. Annotations show up while
  the job is `in_progress`, and the plugin's title and summary are still there
  after the job completes.
- `::error` workflow commands do **not** stream. The runner queues them straight
  away, but GitHub shows them only once the job finishes, and caps them at 10
  per step. That mode was removed.

## The experiment

`.github/workflows/streaming-failures-experiment.yml` runs on push:

- **Stream via check run API** runs the demo suite (`maxRetries = 2`).
  Per case: a third always fail, a third fail only on the first attempt, and a
  third pass. There are also quick always-failing, flaky and nested-class tests,
  and `ParameterisedFlakesTest`, whose invocations flake differently from one
  another, repeat display names across methods, or change their display name
  on every attempt.
- **Observe annotations while tests run** polls the check run every 10 s and
  writes a timeline to its job summary.
- **Inspect check runs after completion** dumps the final title, summary and
  annotations.

Run locally against a stub API:

```bash
STREAMING_FAILURES_MODE=check-run CHECK_RUN_ID=1 GITHUB_TOKEN=x GITHUB_REPOSITORY=o/r \
  GITHUB_API_URL=http://127.0.0.1:18765 ./gradlew --console=plain test -Pdemo.cases=6 -Pdemo.delayMillis=700
```

## Open questions before using this for real

- **Configuration cache.** The listener holds `Provider`s plus plain `File`s.
  Not yet checked with the configuration cache on.
- **Rate limits.** At most one PATCH per flush interval per job, plus one per 50
  new annotations.
- **Jobs that run tests inside a container.** `CHECK_RUN_ID` and `GITHUB_TOKEN`
  have to be passed through into the container.
