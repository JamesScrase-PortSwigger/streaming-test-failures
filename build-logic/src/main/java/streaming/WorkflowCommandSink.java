package streaming;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

class WorkflowCommandSink implements FailureSink {
    private static final Logger LOGGER = Logging.getLogger(WorkflowCommandSink.class);

    @Override
    public void accept(FailedTest failure) {
        SourceLocator.Location location = failure.location();
        LOGGER.quiet("::error file={},line={},title={}::{}",
                escapeProperty(location.path()),
                location.line(),
                escapeProperty(failure.title()),
                escapeData(failure.shortMessage()));
    }

    @Override
    public String description() {
        return "::error workflow commands";
    }

    private static String escapeData(String value) {
        return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    private static String escapeProperty(String value) {
        return escapeData(value).replace(":", "%3A").replace(",", "%2C");
    }
}
