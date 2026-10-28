package dev.raqueeb.cadence;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.near;
import static dev.raqueeb.cadence.harness.Assert.notEqual;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;

/**
 * Tests for the test harness.
 *
 * <p>Worth having rather than circular: the harness is the one piece of code
 * that cannot be caught by a failing test elsewhere. An assertion that never
 * fails would make the whole suite green regardless of the code under it, so
 * each assertion is checked here for failing when it should.
 */
final class HarnessTest {

    @Test("a passing assertion does not throw")
    static void passingAssertionsAreSilent() {
        isTrue(true, "true to be true");
        isFalse(false, "false to be true");
        equal("a", "a", "equal strings");
        equal(7L, 7L, "equal longs");
        notEqual("a", "b", "differing strings");
        near(1.0, 1.0001, 0.001, "near doubles");
    }

    @Test("a failing assertion throws AssertionError")
    static void failingAssertionsThrow() {
        throws_(AssertionError.class, () -> isTrue(false, "the impossible"));
        throws_(AssertionError.class, () -> equal("a", "b", "mismatched"));
        throws_(AssertionError.class, () -> equal(1L, 2L, "mismatched longs"));
        throws_(AssertionError.class, () -> near(1.0, 2.0, 0.001, "distant doubles"));
    }

    @Test("a failure message carries both values")
    static void failureMessagesCarryBothValues() {
        AssertionError error =
                throws_(AssertionError.class, () -> equal("wanted", "found", "the value"));

        contains(error.getMessage(), "wanted", "the message names the expected value");
        contains(error.getMessage(), "found", "the message names the actual value");
        contains(error.getMessage(), "the value", "the message carries the description");
    }

    @Test("NaN is never near anything")
    static void nanIsNeverNear() {
        // Guard rail: NaN fails every comparison, so a plain abs-difference
        // check would quietly pass. A metric that has gone NaN is exactly the
        // bug a tolerance assertion is there to catch.
        throws_(AssertionError.class, () -> near(1.0, Double.NaN, 1000.0, "a NaN metric"));
    }

    @Test("throws_ rejects the wrong exception type")
    static void throwsRejectsTheWrongType() {
        AssertionError error = throws_(AssertionError.class,
                () -> throws_(IllegalStateException.class, () -> {
                    throw new IllegalArgumentException("not the one");
                }));

        contains(error.getMessage(), "IllegalStateException", "the message names what was wanted");
        contains(error.getMessage(), "IllegalArgumentException", "and what arrived");
    }

    @Test("throws_ fails when nothing is thrown")
    static void throwsFailsWhenNothingIsThrown() {
        AssertionError error = throws_(AssertionError.class,
                () -> throws_(IllegalStateException.class, () -> {
                }));

        contains(error.getMessage(), "nothing was thrown", "the message says nothing was thrown");
    }

    @Test("the version is reported as name and number")
    static void identityReadsAsNameAndVersion() {
        equal("cadence " + Cadence.VERSION, Cadence.identity(), "the identity string");
    }
}
