package streaming;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

record SourceLocator(File rootDirectory, List<File> sourceDirectories, File fallbackFile) implements Serializable {
    private static final List<String> SOURCE_EXTENSIONS = List.of(".java", ".kt", ".groovy");

    record Location(String path, int line) {
    }

    Location locate(String className, String testName, List<Throwable> exceptions) {
        String topLevelClassName = topLevelClassName(className);

        return sourceFileFor(topLevelClassName)
                .map(file -> new Location(relativePath(file), lineFor(file, topLevelClassName, testName, exceptions)))
                .orElseGet(() -> new Location(relativePath(fallbackFile), 1));
    }

    private Optional<File> sourceFileFor(String topLevelClassName) {
        String relativeStem = topLevelClassName.replace('.', '/');

        return sourceDirectories.stream()
                .flatMap(directory -> SOURCE_EXTENSIONS.stream().map(extension -> new File(directory, relativeStem + extension)))
                .filter(File::isFile)
                .findFirst();
    }

    private static int lineFor(File file, String topLevelClassName, String testName, List<Throwable> exceptions) {
        return lineFromStackTrace(topLevelClassName, exceptions)
                .or(() -> lineMatching(file, Pattern.compile("\\b" + Pattern.quote(methodName(testName)) + "\\s*\\(")))
                .or(() -> lineMatching(file, Pattern.compile("\\bclass\\s+" + Pattern.quote(simpleName(topLevelClassName)) + "\\b")))
                .orElse(1);
    }

    private static Optional<Integer> lineFromStackTrace(String topLevelClassName, List<Throwable> exceptions) {
        return exceptions.stream()
                .flatMap(exception -> List.of(exception.getStackTrace()).stream())
                .filter(frame -> frame.getClassName().equals(topLevelClassName) || frame.getClassName().startsWith(topLevelClassName + "$"))
                .map(StackTraceElement::getLineNumber)
                .filter(line -> line > 0)
                .findFirst();
    }

    private static Optional<Integer> lineMatching(File file, Pattern pattern) {
        List<String> lines = readLines(file);
        for (int index = 0; index < lines.size(); index++) {
            if (pattern.matcher(lines.get(index)).find()) {
                return Optional.of(index + 1);
            }
        }
        return Optional.empty();
    }

    private static List<String> readLines(File file) {
        try {
            return Files.readAllLines(file.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String relativePath(File file) {
        return rootDirectory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    private static String topLevelClassName(String className) {
        int nestedSeparator = className.indexOf('$');
        return nestedSeparator < 0 ? className : className.substring(0, nestedSeparator);
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String methodName(String testName) {
        int parenthesis = testName.indexOf('(');
        return parenthesis < 0 ? testName : testName.substring(0, parenthesis);
    }
}
