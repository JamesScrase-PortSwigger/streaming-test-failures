package streaming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static streaming.Annotation.Level.FAILURE;
import static streaming.Annotation.Level.WARNING;

class FailureTracker {
    private static final int SUMMARY_TESTS_PER_SECTION = 50;
    private static final int MAX_SUMMARY_LENGTH = 60_000;

    private final Map<TestKey, History> histories = new LinkedHashMap<>();
    private final List<Annotation> unpublished = new ArrayList<>();
    private boolean buildFinished;
    private long version;

    private static final class History {
        private FailedTest lastFailure;
        private int failedAttempts;
        private boolean passedOnRetry;
        private boolean finalised;

        private boolean awaitingRetry() {
            return !finalised && !passedOnRetry;
        }

        private boolean failed() {
            return finalised && !passedOnRetry;
        }
    }

    synchronized void failed(FailedTest failure, RetryPolicy retryPolicy) {
        History history = histories.computeIfAbsent(failure.key(), key -> new History());
        history.lastFailure = failure;
        history.failedAttempts++;
        history.passedOnRetry = false;

        if (retryPolicy.isLastAttempt(history.failedAttempts)) {
            markFailed(history);
        } else if (history.failedAttempts == 1) {
            unpublished.add(new Annotation(failure, WARNING, failure.title() + " — failed, may be retried"));
        }
        version++;
    }

    synchronized void passed(TestKey key) {
        History history = histories.get(key);
        if (history != null && !history.passedOnRetry) {
            history.passedOnRetry = true;
            version++;
        }
    }

    synchronized void taskFinished(String taskPath) {
        markRemainingFailed(history -> history.lastFailure.taskPath().equals(taskPath));
    }

    synchronized void buildFinished() {
        buildFinished = true;
        markRemainingFailed(history -> true);
        version++;
    }

    synchronized List<Annotation> drainAnnotations() {
        List<Annotation> drained = List.copyOf(unpublished);
        unpublished.clear();
        return drained;
    }

    synchronized long version() {
        return version;
    }

    synchronized Report report() {
        List<History> failed = matching(History::failed);
        List<History> awaitingRetry = matching(History::awaitingRetry);
        List<History> flaky = matching(history -> history.passedOnRetry);

        String summary = section("Failed", failed)
                + section("Failed, may be retried", awaitingRetry)
                + section("Passed on retry (flaky)", flaky);

        return new Report(title(failed.size(), awaitingRetry.size(), flaky.size()), truncate(summary.isEmpty() ? "No test failures." : summary));
    }

    private void markRemainingFailed(Predicate<History> inScope) {
        histories.values().stream()
                .filter(History::awaitingRetry)
                .filter(inScope)
                .forEach(this::markFailed);
        version++;
    }

    private void markFailed(History history) {
        history.finalised = true;
        FailedTest failure = history.lastFailure;
        String attempts = history.failedAttempts == 1 ? "failed" : "failed all " + history.failedAttempts + " attempts";
        unpublished.add(new Annotation(failure, FAILURE, failure.title() + " — " + attempts));
    }

    private List<History> matching(Predicate<History> predicate) {
        return histories.values().stream().filter(predicate).toList();
    }

    private String title(int failed, int awaitingRetry, int flaky) {
        List<String> parts = new ArrayList<>();
        if (failed > 0) {
            parts.add(failed + " failed");
        }
        if (awaitingRetry > 0) {
            parts.add(awaitingRetry + " may be retried");
        }
        if (flaky > 0) {
            parts.add(flaky + " flaky");
        }
        if (parts.isEmpty()) {
            return buildFinished ? "No test failures" : "No test failures so far";
        }
        return String.join(" · ", parts) + (buildFinished ? "" : " (still running)");
    }

    private static String section(String heading, List<History> histories) {
        if (histories.isEmpty()) {
            return "";
        }
        String listed = histories.stream()
                .limit(SUMMARY_TESTS_PER_SECTION)
                .map(history -> "- `" + history.lastFailure.title() + "` (" + history.lastFailure.taskPath() + ", " + history.failedAttempts + " failed attempt" + (history.failedAttempts == 1 ? "" : "s") + ")")
                .collect(Collectors.joining("\n"));
        String overflow = histories.size() > SUMMARY_TESTS_PER_SECTION ? "\n- … and " + (histories.size() - SUMMARY_TESTS_PER_SECTION) + " more" : "";
        return "### " + heading + " (" + histories.size() + ")\n\n" + listed + overflow + "\n\n";
    }

    private static String truncate(String value) {
        return value.length() <= MAX_SUMMARY_LENGTH ? value : value.substring(0, MAX_SUMMARY_LENGTH - 1) + "…";
    }
}
