package dev.raqueeb.cadence.harness;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Assertions that say what went wrong.
 *
 * <p>Every failure message carries the expected value, the actual value and
 * the caller's own description. A message of "assertion failed" costs more in
 * debugging time than the message costs to write.
 */
public final class Assert {

    private Assert() {
    }

    /** Fails with {@code message}. */
    public static void fail(String message) {
        throw new AssertionError(message);
    }

    /** Asserts that {@code condition} holds. */
    public static void isTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("expected " + what);
        }
    }

    /** Asserts that {@code condition} does not hold. */
    public static void isFalse(boolean condition, String what) {
        if (condition) {
            throw new AssertionError("expected not " + what);
        }
    }

    /** Asserts that two values are equal, printing both when they are not. */
    public static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    what + ": expected <" + render(expected) + "> but was <" + render(actual) + ">");
        }
    }

    /** Asserts that two values differ. */
    public static void notEqual(Object unwanted, Object actual, String what) {
        if (Objects.equals(unwanted, actual)) {
            throw new AssertionError(what + ": expected anything but <" + render(unwanted) + ">");
        }
    }

    /** Asserts that two {@code long}s are equal. */
    public static void equal(long expected, long actual, String what) {
        if (expected != actual) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    /**
     * Asserts that two doubles are within {@code tolerance} of each other.
     *
     * <p>A tolerance is required rather than defaulted, because the right one
     * depends on what is being compared and a silent default hides that.
     */
    public static void near(double expected, double actual, double tolerance, String what) {
        if (Double.isNaN(actual) || Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(
                    what + ": expected " + expected + " +/- " + tolerance + " but was " + actual);
        }
    }

    /** Asserts that a value is null. */
    public static void isNull(Object actual, String what) {
        if (actual != null) {
            throw new AssertionError(what + ": expected null but was <" + render(actual) + ">");
        }
    }

    /** Asserts that a value is not null. */
    public static void notNull(Object actual, String what) {
        if (actual == null) {
            throw new AssertionError(what + ": expected a value but was null");
        }
    }

    /** Asserts that a collection is empty. */
    public static void empty(List<?> actual, String what) {
        if (!actual.isEmpty()) {
            throw new AssertionError(what + ": expected empty but held " + render(actual));
        }
    }

    /** Asserts that two arrays hold equal elements. */
    public static void arrayEqual(int[] expected, int[] actual, String what) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(what + ": expected " + Arrays.toString(expected)
                    + " but was " + Arrays.toString(actual));
        }
    }

    /**
     * Asserts that {@code body} throws {@code expected}, and returns it.
     *
     * <p>Returning the exception lets the caller go on to assert something
     * about its message, which is usually the part worth pinning: that a
     * failure happened matters less than whether it explains itself.
     *
     * @param <E> the exception type demanded
     * @return the thrown exception, for further assertions
     */
    public static <E extends Throwable> E throws_(Class<E> expected, ThrowingBody body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return expected.cast(thrown);
            }
            throw new AssertionError("expected " + expected.getSimpleName() + " but threw "
                    + thrown.getClass().getSimpleName() + ": " + thrown.getMessage(), thrown);
        }
        throw new AssertionError("expected " + expected.getSimpleName() + " but nothing was thrown");
    }

    /** Asserts that {@code haystack} contains {@code needle}. */
    public static void contains(String haystack, String needle, String what) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(
                    what + ": expected <" + render(haystack) + "> to contain <" + needle + ">");
        }
    }

    /** A body that may throw anything, so a test can demand a checked exception. */
    @FunctionalInterface
    public interface ThrowingBody {
        /**
         * Runs the body.
         *
         * @throws Exception whatever the body under test throws
         */
        void run() throws Exception;
    }

    /** Renders a value for a message, keeping arrays readable. */
    private static String render(Object value) {
        if (value instanceof int[] ints) {
            return Arrays.toString(ints);
        }
        if (value instanceof long[] longs) {
            return Arrays.toString(longs);
        }
        if (value instanceof Object[] objects) {
            return Arrays.deepToString(objects);
        }
        return String.valueOf(value);
    }
}
