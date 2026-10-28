package dev.raqueeb.cadence.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds and runs every {@link Test} method on the test classpath.
 *
 * <p>Discovery walks the directory this class was loaded from looking for
 * {@code *Test.class}, rather than keeping a registry of test classes. A
 * registry is one more thing to forget to update, and a test that silently
 * never runs is worse than no test at all.
 *
 * <p>A single argument filters by substring, so one case can be run on its
 * own while working on it:
 *
 * <pre>{@code
 * java -cp build/classes:build/test-classes \
 *     dev.raqueeb.cadence.harness.TestRunner cron
 * }</pre>
 */
public final class TestRunner {

    private TestRunner() {
    }

    /**
     * Runs the suite and exits non-zero if anything failed.
     *
     * @param args an optional substring filter on class and method names
     */
    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0].toLowerCase() : "";

        List<Class<?>> classes = discover();
        if (classes.isEmpty()) {
            System.err.println("no test classes found; was the test source compiled?");
            System.exit(2);
        }

        int passed = 0;
        List<Failure> failures = new ArrayList<>();
        long start = System.nanoTime();

        for (Class<?> testClass : classes) {
            List<Method> methods = testMethods(testClass);
            if (methods.isEmpty()) {
                continue;
            }

            boolean printedHeader = false;
            for (Method method : methods) {
                String label = describe(method);
                if (!matches(filter, testClass, method, label)) {
                    continue;
                }

                if (!printedHeader) {
                    System.out.println();
                    System.out.println(testClass.getSimpleName());
                    printedHeader = true;
                }

                try {
                    method.invoke(null);
                    passed++;
                    System.out.println("  ok    " + label);
                } catch (InvocationTargetException wrapper) {
                    Throwable cause = wrapper.getCause() == null ? wrapper : wrapper.getCause();
                    failures.add(new Failure(testClass.getSimpleName(), label, cause));
                    System.out.println("  FAIL  " + label);
                } catch (IllegalAccessException e) {
                    failures.add(new Failure(testClass.getSimpleName(), label, e));
                    System.out.println("  FAIL  " + label + " (not accessible)");
                }
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        report(passed, failures, elapsedMs);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** Prints the failure details, then the summary line. */
    private static void report(int passed, List<Failure> failures, long elapsedMs) {
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Failures");
            for (Failure failure : failures) {
                System.out.println();
                System.out.println("  " + failure.testClass + " / " + failure.label);
                System.out.println("    " + failure.cause);
                // Only the frames inside the project are printed. A full trace
                // here is mostly reflection plumbing, which buries the line
                // that actually failed.
                for (StackTraceElement frame : failure.cause.getStackTrace()) {
                    if (frame.getClassName().startsWith("dev.raqueeb.cadence")) {
                        System.out.println("      at " + frame);
                    }
                }
            }
        }

        System.out.println();
        System.out.printf("%d passed, %d failed in %d ms%n", passed, failures.size(), elapsedMs);
    }

    /** Whether a filter matches the class name, the method name or the description. */
    private static boolean matches(String filter, Class<?> testClass, Method method, String label) {
        if (filter.isEmpty()) {
            return true;
        }
        return testClass.getSimpleName().toLowerCase().contains(filter)
                || method.getName().toLowerCase().contains(filter)
                || label.toLowerCase().contains(filter);
    }

    /** The description if the annotation carries one, else the method name. */
    private static String describe(Method method) {
        String described = method.getAnnotation(Test.class).value();
        return described.isEmpty() ? method.getName() : described;
    }

    /**
     * The {@code @Test} methods of a class, in source-file order where the JVM
     * reports it, else alphabetical so a run is reproducible.
     */
    private static List<Method> testMethods(Class<?> testClass) {
        List<Method> methods = new ArrayList<>();
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Test.class)) {
                method.setAccessible(true);
                methods.add(method);
            }
        }
        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    /** Every {@code *Test} class under the directory this class was loaded from. */
    private static List<Class<?>> discover() {
        Path root = classpathRoot();
        List<Class<?>> classes = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> candidates = walk
                    .filter(path -> path.getFileName().toString().endsWith("Test.class"))
                    .sorted()
                    .toList();

            for (Path path : candidates) {
                String relative = root.relativize(path).toString()
                        .replace('\\', '/')
                        .replace(".class", "");
                String className = relative.replace('/', '.');
                try {
                    classes.add(Class.forName(className));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("found " + path + " but could not load it", e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not scan " + root + " for tests", e);
        }

        return classes;
    }

    /** The directory holding the compiled test classes. */
    private static Path classpathRoot() {
        try {
            return Path.of(TestRunner.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("could not locate the test classes directory", e);
        }
    }

    /** One failed case, kept so every failure is reported rather than the first. */
    private record Failure(String testClass, String label, Throwable cause) {
    }
}
