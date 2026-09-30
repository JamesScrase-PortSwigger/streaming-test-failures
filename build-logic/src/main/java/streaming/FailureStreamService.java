package streaming;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFinishEvent;

public abstract class FailureStreamService implements BuildService<FailureStreamService.Params>, OperationCompletionListener, AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(FailureStreamService.class);

    public interface Params extends BuildServiceParameters {
        Property<String> getMode();

        Property<Integer> getFlushIntervalSeconds();

        Property<String> getCheckRunId();

        Property<String> getToken();

        Property<String> getRepository();

        Property<String> getApiUrl();
    }

    private final FailureTracker tracker = new FailureTracker();
    private CheckRunPublisher publisher;
    private boolean publisherResolved;

    void failed(FailedTest failure, RetryPolicy retryPolicy) {
        startPublishing();
        tracker.failed(failure, retryPolicy);
    }

    void passed(TestKey key) {
        tracker.passed(key);
    }

    @Override
    public void onFinish(FinishEvent event) {
        if (event instanceof TaskFinishEvent taskFinished) {
            tracker.taskFinished(taskFinished.getDescriptor().getTaskPath());
        }
    }

    @Override
    public void close() {
        tracker.buildFinished();
        if (publisher != null) {
            publisher.close();
        }
    }

    private synchronized void startPublishing() {
        if (publisherResolved) {
            return;
        }
        publisherResolved = true;
        if (shouldPublish()) {
            Params parameters = getParameters();
            publisher = new CheckRunPublisher(
                    tracker,
                    parameters.getApiUrl().get(),
                    parameters.getRepository().get(),
                    parameters.getCheckRunId().get(),
                    parameters.getToken().get(),
                    parameters.getFlushIntervalSeconds().get());
            LOGGER.lifecycle("[streaming-failures] publishing test failures as {}", publisher.description());
        }
    }

    private boolean shouldPublish() {
        Params parameters = getParameters();
        String mode = parameters.getMode().get();
        boolean configured = parameters.getCheckRunId().isPresent() && parameters.getToken().isPresent() && parameters.getRepository().isPresent();
        return switch (mode) {
            case "check-run" -> requireConfigured(configured);
            case "auto" -> configured;
            case "off" -> false;
            default -> throw new IllegalArgumentException("Unknown streaming failure mode: " + mode);
        };
    }

    private static boolean requireConfigured(boolean configured) {
        if (!configured) {
            throw new IllegalStateException("check-run mode needs CHECK_RUN_ID, GITHUB_TOKEN and GITHUB_REPOSITORY");
        }
        return true;
    }
}
