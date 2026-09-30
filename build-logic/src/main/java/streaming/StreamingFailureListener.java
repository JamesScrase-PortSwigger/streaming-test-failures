package streaming;

import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;

import java.util.List;

import static org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE;

class StreamingFailureListener implements TestListener {
    private final Provider<FailureStreamService> service;
    private final SourceLocator sourceLocator;
    private final String taskPath;

    StreamingFailureListener(Provider<FailureStreamService> service, SourceLocator sourceLocator, String taskPath) {
        this.service = service;
        this.sourceLocator = sourceLocator;
        this.taskPath = taskPath;
    }

    @Override
    public void afterTest(TestDescriptor test, TestResult result) {
        if (result.getResultType() == FAILURE && test.getClassName() != null) {
            report(test, result.getExceptions());
        }
    }

    @Override
    public void afterSuite(TestDescriptor suite, TestResult result) {
        if (isClassLevelFailure(suite, result)) {
            report(suite, result.getExceptions());
        }
    }

    private static boolean isClassLevelFailure(TestDescriptor suite, TestResult result) {
        return result.getResultType() == FAILURE && suite.getClassName() != null && result.getFailedTestCount() == 0;
    }

    private void report(TestDescriptor test, List<Throwable> exceptions) {
        service.get().report(FailedTest.from(taskPath, test, exceptions, sourceLocator));
    }

    @Override
    public void beforeSuite(TestDescriptor suite) {
    }

    @Override
    public void beforeTest(TestDescriptor test) {
    }
}
