package streaming;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

class CheckRunSink implements FailureSink {
    private static final Logger LOGGER = Logging.getLogger(CheckRunSink.class);
    private static final int MAX_ANNOTATIONS_PER_REQUEST = 50;
    private static final int MAX_ANNOTATION_FIELD_LENGTH = 60_000;
    private static final int MAX_SUMMARY_LENGTH = 60_000;
    private static final int SUMMARY_FAILURES_LISTED = 100;

    private final URI checkRunUri;
    private final String token;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "streaming-failures-flusher");
        thread.setDaemon(true);
        return thread;
    });
    private final Queue<FailedTest> received = new ConcurrentLinkedQueue<>();
    private final List<FailedTest> unpublished = new ArrayList<>();
    private final List<FailedTest> published = new ArrayList<>();
    private boolean finished;

    CheckRunSink(String apiUrl, String repository, String checkRunId, String token, int flushIntervalSeconds) {
        this.checkRunUri = URI.create(apiUrl + "/repos/" + repository + "/check-runs/" + checkRunId);
        this.token = token;
        flusher.scheduleWithFixedDelay(this::flush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void accept(FailedTest failure) {
        received.add(failure);
    }

    @Override
    public String description() {
        return "annotations on check run " + checkRunUri;
    }

    @Override
    public void close() {
        flusher.shutdown();
        awaitFlusher();
        finish();
    }

    private synchronized void finish() {
        finished = true;
        int publishedBefore = published.size();
        flush();
        if (published.size() == publishedBefore && !published.isEmpty()) {
            patch(List.of());
        }
    }

    private synchronized void flush() {
        for (FailedTest failure = received.poll(); failure != null; failure = received.poll()) {
            unpublished.add(failure);
        }
        while (!unpublished.isEmpty()) {
            List<FailedTest> batch = List.copyOf(unpublished.subList(0, Math.min(MAX_ANNOTATIONS_PER_REQUEST, unpublished.size())));
            if (!patch(batch)) {
                return;
            }
            unpublished.subList(0, batch.size()).clear();
            published.addAll(batch);
        }
    }

    private boolean patch(List<FailedTest> batch) {
        int failuresAfterBatch = published.size() + batch.size();
        String body = requestBody(batch, failuresAfterBatch);
        HttpRequest request = HttpRequest.newBuilder(checkRunUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean succeeded = response.statusCode() / 100 == 2;
            if (succeeded) {
                LOGGER.lifecycle("[streaming-failures] PATCH {} -> {} (+{} annotations, {} total)", checkRunUri, response.statusCode(), batch.size(), failuresAfterBatch);
            } else {
                LOGGER.warn("[streaming-failures] PATCH {} -> {}: {}", checkRunUri, response.statusCode(), response.body());
            }
            return succeeded;
        } catch (IOException e) {
            LOGGER.warn("[streaming-failures] PATCH {} failed: {}", checkRunUri, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String requestBody(List<FailedTest> batch, int failuresAfterBatch) {
        String annotations = batch.stream().map(CheckRunSink::annotation).collect(Collectors.joining(","));
        List<FailedTest> allFailures = new ArrayList<>(published);
        allFailures.addAll(batch);

        return "{\"output\":{"
                + "\"title\":" + Json.string(failuresAfterBatch + " test failure" + (failuresAfterBatch == 1 ? "" : "s") + (finished ? "" : " so far")) + ","
                + "\"summary\":" + Json.string(summary(allFailures, finished)) + ","
                + "\"annotations\":[" + annotations + "]"
                + "}}";
    }

    private static String annotation(FailedTest failure) {
        SourceLocator.Location location = failure.location();
        return "{"
                + "\"path\":" + Json.string(location.path()) + ","
                + "\"start_line\":" + location.line() + ","
                + "\"end_line\":" + location.line() + ","
                + "\"annotation_level\":\"failure\","
                + "\"title\":" + Json.string(truncate(failure.title(), 255)) + ","
                + "\"message\":" + Json.string(truncate(failure.shortMessage(), MAX_ANNOTATION_FIELD_LENGTH)) + ","
                + "\"raw_details\":" + Json.string(truncate(failure.stackTrace(), MAX_ANNOTATION_FIELD_LENGTH))
                + "}";
    }

    private static String summary(List<FailedTest> failures, boolean finished) {
        String listed = failures.stream()
                .limit(SUMMARY_FAILURES_LISTED)
                .map(failure -> "- `" + failure.title() + "` (" + failure.taskPath() + ", " + failure.failedAt() + ")")
                .collect(Collectors.joining("\n"));
        String overflow = failures.size() > SUMMARY_FAILURES_LISTED
                ? "\n- … and " + (failures.size() - SUMMARY_FAILURES_LISTED) + " more"
                : "";
        return truncate((finished ? "Tests finished. Failures, in the order they happened:" : "Failures streamed while the tests are still running:") + "\n\n" + listed + overflow, MAX_SUMMARY_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    private void awaitFlusher() {
        try {
            flusher.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
