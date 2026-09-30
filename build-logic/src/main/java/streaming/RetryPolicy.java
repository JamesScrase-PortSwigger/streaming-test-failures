package streaming;

import java.io.Serializable;

record RetryPolicy(int maxRetries) implements Serializable {
    static final RetryPolicy NO_RETRIES = new RetryPolicy(0);

    boolean isLastAttempt(int failedAttempts) {
        return failedAttempts > maxRetries;
    }
}
