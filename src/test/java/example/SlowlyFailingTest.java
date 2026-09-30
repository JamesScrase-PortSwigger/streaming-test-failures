package example;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class SlowlyFailingTest {
    private static final int CASES = Integer.getInteger("demo.cases", 9);
    private static final long DELAY_MILLIS = Long.getLong("demo.delayMillis", 6_000);

    @Test
    void givenAnEarlyAssertion_thenItFailsEveryAttempt() {
        assertEquals("expected", "actual", "fails every attempt, so it should end as a failure");
    }

    @Test
    void givenAFlakyDependency_thenOnlyTheFirstAttemptFails() throws IOException {
        if (attempt("quick-flaky") == 1) {
            fail("first attempt fails, the retry passes");
        }
    }

    @TestFactory
    Stream<DynamicTest> slowCases() {
        return IntStream.rangeClosed(1, CASES).mapToObj(index -> dynamicTest("case " + index, () -> {
            int attempt = attempt("case-" + index);
            Thread.sleep(DELAY_MILLIS);
            boolean alwaysFails = index % 3 == 1;
            boolean flaky = index % 3 == 2;
            if (alwaysFails || (flaky && attempt == 1)) {
                fail("case " + index + " failed on attempt " + attempt + " at " + Instant.now());
            }
        }));
    }

    @Nested
    class NestedCases {
        @Test
        void givenANestedClass_thenTheAnnotationPointsAtTheOuterFile() {
            throw new IllegalStateException("nested failure");
        }
    }

    private static int attempt(String name) throws IOException {
        Path file = Path.of(System.getProperty("demo.attemptsDir")).resolve(name);
        Files.createDirectories(file.getParent());
        int attempt = Files.exists(file) ? Integer.parseInt(Files.readString(file)) + 1 : 1;
        Files.writeString(file, Integer.toString(attempt));
        return attempt;
    }
}
