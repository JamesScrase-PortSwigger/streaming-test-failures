package streaming;

import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;

import java.util.List;

import static org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE;
import static org.gradle.api.tasks.testing.TestResult.ResultType.SUCCESS;

class StreamingFailureListener implements TestListener {
    private final Provider<FailureStreamService> service;
    private final SourceLocator sourceLocator;
    private final String taskPath;
    private final Provider<RetryPolicy> retryPolicy;

    StreamingFailureListener(Provider<FailureStreamService> service, SourceLocator sourceLocator, String taskPath, Provider<RetryPolicy> retryPolicy) {
        this.service = service;
        this.sourceLocator = sourceLocator;
        this.taskPath = taskPath;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public void afterTest(TestDescriptor test, TestResult result) {
        if (test.getClassName() == null) {
            return;
        }
        if (result.getResultType() == FAILURE) {
            reportFailure(test, result.getExceptions());
        } else if (result.getResultType() == SUCCESS) {
            service.get().passed(new TestKey(taskPath, test.getClassName(), test.getDisplayName()));
        }
    }

    @Override
    public void afterSuite(TestDescriptor suite, TestResult result) {
        if (isClassLevelFailure(suite, result)) {
            reportFailure(suite, result.getExceptions());
        }
    }

    private static boolean isClassLevelFailure(TestDescriptor suite, TestResult result) {
        return result.getResultType() == FAILURE && suite.getClassName() != null && result.getFailedTestCount() == 0;
    }

    private void reportFailure(TestDescriptor test, List<Throwable> exceptions) {
        service.get().failed(FailedTest.from(taskPath, test, exceptions, sourceLocator), retryPolicy.get());
    }

    @Override
    public void beforeSuite(TestDescriptor suite) {
    }

    @Override
    public void beforeTest(TestDescriptor test) {
    }
}
