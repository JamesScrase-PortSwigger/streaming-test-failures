package example;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class SlowlyFailingTest {
    private static final int CASES = Integer.getInteger("demo.cases", 18);
    private static final long DELAY_MILLIS = Long.getLong("demo.delayMillis", 10_000);

    @Test
    void givenAnEarlyAssertion_thenItFailsBeforeTheSlowCases() {
        assertEquals("expected", "actual", "fails first, so the earliest annotation should point at this line");
    }

    @TestFactory
    Stream<DynamicTest> slowCasesFailingTwoInThree() {
        return IntStream.rangeClosed(1, CASES).mapToObj(index -> dynamicTest("case " + index, () -> {
            Thread.sleep(DELAY_MILLIS);
            if (index % 3 != 0) {
                fail("case " + index + " failed at " + Instant.now());
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
}
