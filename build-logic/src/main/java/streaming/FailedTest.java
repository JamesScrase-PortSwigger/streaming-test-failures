package streaming;

import org.gradle.api.tasks.testing.TestDescriptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

record FailedTest(String taskPath, String className, String testName, String headline, String stackTrace, SourceLocator.Location location, Instant failedAt) {
    private static final int HEADLINE_FRAMES = 12;

    static FailedTest from(String taskPath, TestDescriptor test, List<Throwable> exceptions, SourceLocator sourceLocator) {
        return new FailedTest(
                taskPath,
                test.getClassName(),
                test.getDisplayName(),
                headline(exceptions),
                stackTraces(exceptions),
                sourceLocator.locate(test.getClassName(), test.getName(), exceptions),
                Instant.now());
    }

    String title() {
        return simpleClassName() + " > " + testName;
    }

    String shortMessage() {
        return headline + "\n\n" + taskPath + " failed at " + failedAt;
    }

    private String simpleClassName() {
        String withoutPackage = className.substring(className.lastIndexOf('.') + 1);
        return withoutPackage.replace('$', '.');
    }

    private static String headline(List<Throwable> exceptions) {
        if (exceptions.isEmpty()) {
            return "Failed without an exception";
        }
        Throwable first = exceptions.getFirst();
        String frames = List.of(first.getStackTrace()).stream()
                .limit(HEADLINE_FRAMES)
                .map(frame -> "    at " + frame)
                .collect(Collectors.joining("\n"));
        return first + "\n" + frames;
    }

    private static String stackTraces(List<Throwable> exceptions) {
        return exceptions.stream()
                .map(FailedTest::stackTrace)
                .collect(Collectors.joining("\n"));
    }

    private static String stackTrace(Throwable exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
