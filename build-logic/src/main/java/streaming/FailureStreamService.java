package streaming;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

public abstract class FailureStreamService implements BuildService<FailureStreamService.Params>, AutoCloseable {
    private static final Logger LOGGER = Logging.getLogger(FailureStreamService.class);

    public interface Params extends BuildServiceParameters {
        Property<String> getMode();

        Property<Integer> getFlushIntervalSeconds();

        Property<String> getCheckRunId();

        Property<String> getToken();

        Property<String> getRepository();

        Property<String> getApiUrl();

        Property<Boolean> getRunningInActions();
    }

    private FailureSink sink;

    void report(FailedTest failure) {
        sink().accept(failure);
    }

    @Override
    public void close() {
        if (sink != null) {
            sink.close();
        }
    }

    private synchronized FailureSink sink() {
        if (sink == null) {
            sink = createSink();
            LOGGER.lifecycle("[streaming-failures] publishing test failures via {}", sink.description());
        }
        return sink;
    }

    private FailureSink createSink() {
        Params parameters = getParameters();
        return switch (resolvedMode(parameters)) {
            case "check-run" -> new CheckRunSink(
                    parameters.getApiUrl().get(),
                    parameters.getRepository().get(),
                    parameters.getCheckRunId().get(),
                    parameters.getToken().get(),
                    parameters.getFlushIntervalSeconds().get());
            case "workflow-command" -> new WorkflowCommandSink();
            case "off" -> FailureSink.NONE;
            default -> throw new IllegalArgumentException("Unknown streaming failure mode: " + parameters.getMode().get());
        };
    }

    private static String resolvedMode(Params parameters) {
        String mode = parameters.getMode().get();
        if (!mode.equals("auto")) {
            return mode;
        }
        if (canUpdateCheckRun(parameters)) {
            return "check-run";
        }
        return parameters.getRunningInActions().get() ? "workflow-command" : "off";
    }

    private static boolean canUpdateCheckRun(Params parameters) {
        return parameters.getCheckRunId().isPresent() && parameters.getToken().isPresent() && parameters.getRepository().isPresent();
    }
}
