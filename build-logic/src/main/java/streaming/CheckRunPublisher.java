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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

class CheckRunPublisher implements AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(CheckRunPublisher.class);
    private static final int MAX_ANNOTATIONS_PER_REQUEST = 50;
    private static final int MAX_ANNOTATION_FIELD_LENGTH = 60_000;

    private final FailureTracker tracker;
    private final URI checkRunUri;
    private final String token;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "streaming-failures-flusher");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Annotation> unsent = new ArrayList<>();
    private long publishedVersion;

    CheckRunPublisher(FailureTracker tracker, String apiUrl, String repository, String checkRunId, String token, int flushIntervalSeconds) {
        this.tracker = tracker;
        this.checkRunUri = URI.create(apiUrl + "/repos/" + repository + "/check-runs/" + checkRunId);
        this.token = token;
        flusher.scheduleWithFixedDelay(this::flush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    String description() {
        return "annotations on check run " + checkRunUri;
    }

    @Override
    public void close() {
        flusher.shutdown();
        awaitFlusher();
        flush();
    }

    private synchronized void flush() {
        long version = tracker.version();
        unsent.addAll(tracker.drainAnnotations());
        if (unsent.isEmpty() && version == publishedVersion) {
            return;
        }

        Report report = tracker.report();
        do {
            List<Annotation> batch = List.copyOf(unsent.subList(0, Math.min(MAX_ANNOTATIONS_PER_REQUEST, unsent.size())));
            if (!patch(report, batch)) {
                return;
            }
            unsent.subList(0, batch.size()).clear();
        } while (!unsent.isEmpty());
        publishedVersion = version;
    }

    private boolean patch(Report report, List<Annotation> batch) {
        HttpRequest request = HttpRequest.newBuilder(checkRunUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(requestBody(report, batch)))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean succeeded = response.statusCode() / 100 == 2;
            if (succeeded) {
                LOGGER.lifecycle("[streaming-failures] PATCH -> {}: \"{}\" (+{} annotations: {})", response.statusCode(), report.title(), batch.size(), levels(batch));
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

    private static String levels(List<Annotation> batch) {
        return batch.stream().map(annotation -> annotation.level().apiValue()).collect(Collectors.groupingBy(level -> level, Collectors.counting())).toString();
    }

    private static String requestBody(Report report, List<Annotation> batch) {
        String annotations = batch.stream().map(CheckRunPublisher::annotation).collect(Collectors.joining(","));
        return "{\"output\":{"
                + "\"title\":" + Json.string(report.title()) + ","
                + "\"summary\":" + Json.string(report.summary()) + ","
                + "\"annotations\":[" + annotations + "]"
                + "}}";
    }

    private static String annotation(Annotation annotation) {
        FailedTest failure = annotation.failure();
        SourceLocator.Location location = failure.location();
        return "{"
                + "\"path\":" + Json.string(location.path()) + ","
                + "\"start_line\":" + location.line() + ","
                + "\"end_line\":" + location.line() + ","
                + "\"annotation_level\":" + Json.string(annotation.level().apiValue()) + ","
                + "\"title\":" + Json.string(truncate(annotation.title(), 255)) + ","
                + "\"message\":" + Json.string(truncate(failure.shortMessage(), MAX_ANNOTATION_FIELD_LENGTH)) + ","
                + "\"raw_details\":" + Json.string(truncate(failure.stackTrace(), MAX_ANNOTATION_FIELD_LENGTH))
                + "}";
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
