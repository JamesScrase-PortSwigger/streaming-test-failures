package example;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

class ParameterisedFlakesTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void givenAParameter_thenEachFlakesDifferently(int parameter) throws IOException {
        int attempt = attempt("values-" + parameter);
        boolean alwaysFails = parameter == 1;
        boolean failsOnlyFirstAttempt = parameter == 2;
        boolean failsOnlySecondAttempt = parameter == 3;
        if (alwaysFails || (failsOnlyFirstAttempt && attempt == 1) || (failsOnlySecondAttempt && attempt == 2)) {
            fail("parameter " + parameter + " failed on attempt " + attempt);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void givenTheSameDisplayNames_thenTheyDoNotCollide(int parameter) throws IOException {
        if (parameter == 1 && attempt("collide-" + parameter) == 1) {
            fail("parameter " + parameter + " fails on its first attempt only");
        }
    }

    @ParameterizedTest
    @MethodSource("unstableNames")
    void givenADisplayNameThatChangesEachAttempt_thenRetriesStillMatch(int parameter) throws IOException {
        if (attempt("unstable-" + parameter) == 1) {
            fail("parameter " + parameter + " fails on its first attempt only");
        }
    }

    static Stream<Named<Integer>> unstableNames() {
        return IntStream.rangeClosed(1, 2).mapToObj(index -> Named.of("item " + index + " built at " + System.nanoTime(), index));
    }

    private static int attempt(String name) throws IOException {
        Path file = Path.of(System.getProperty("demo.attemptsDir")).resolve(name);
        Files.createDirectories(file.getParent());
        int attempt = Files.exists(file) ? Integer.parseInt(Files.readString(file)) + 1 : 1;
        Files.writeString(file, Integer.toString(attempt));
        return attempt;
    }
}
