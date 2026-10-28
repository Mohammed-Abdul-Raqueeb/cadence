package dev.raqueeb.cadence.job;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.near;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import java.time.Duration;
import java.util.Random;

/** Retry limits, geometric backoff and jitter. */
final class RetryPolicyTest {

    /** A policy with no jitter, so delays can be asserted exactly. */
    private static RetryPolicy plain() {
        return RetryPolicy.builder()
                .maxAttempts(6)
                .baseDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofMinutes(10))
                .multiplier(2.0)
                .jitter(0.0)
                .build();
    }

    // ---- attempt limits ---------------------------------------------------

    @Test("maxAttempts counts the first try")
    static void maxAttemptsCountsTheFirstTry() {
        // Off-by-one bait: "3 attempts" is one try and two retries, not four
        // runs.
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).build();

        isTrue(policy.shouldRetry(1), "a retry after one attempt");
        isTrue(policy.shouldRetry(2), "a retry after two attempts");
        isFalse(policy.shouldRetry(3), "a retry after three attempts");
    }

    @Test("a policy of none never retries")
    static void noneNeverRetries() {
        RetryPolicy policy = RetryPolicy.none();

        equal(1, policy.maxAttempts(), "the attempt limit");
        isFalse(policy.shouldRetry(1), "a retry after the only attempt");
        equal("no retries", policy.toString(), "the description");
    }

    @Test("fewer than one attempt is rejected")
    static void zeroAttemptsIsRejected() {
        IllegalArgumentException error = throws_(IllegalArgumentException.class,
                () -> RetryPolicy.builder().maxAttempts(0));

        contains(error.getMessage(), "at least 1", "the message states the minimum");
    }

    // ---- geometric growth -------------------------------------------------

    @Test("the first attempt waits no time at all")
    static void firstAttemptHasNoDelay() {
        isTrue(plain().baseDelayBefore(1).isZero(), "the delay before the first attempt");
        isTrue(plain().baseDelayBefore(0).isZero(), "the delay before attempt zero");
    }

    @Test("the delay doubles with each attempt")
    static void delayDoubles() {
        RetryPolicy policy = plain();

        equal(1_000L, policy.baseDelayBefore(2).toMillis(), "before the second attempt");
        equal(2_000L, policy.baseDelayBefore(3).toMillis(), "before the third");
        equal(4_000L, policy.baseDelayBefore(4).toMillis(), "before the fourth");
        equal(8_000L, policy.baseDelayBefore(5).toMillis(), "before the fifth");
    }

    @Test("a multiplier of one gives a constant delay")
    static void multiplierOfOneIsConstant() {
        RetryPolicy policy = RetryPolicy.builder()
                .baseDelay(Duration.ofSeconds(5))
                .maxDelay(Duration.ofMinutes(1))
                .multiplier(1.0)
                .jitter(0.0)
                .build();

        equal(5_000L, policy.baseDelayBefore(2).toMillis(), "before the second attempt");
        equal(5_000L, policy.baseDelayBefore(9).toMillis(), "before the ninth");
    }

    @Test("the delay is capped at maxDelay")
    static void delayIsCapped() {
        RetryPolicy policy = RetryPolicy.builder()
                .baseDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofSeconds(5))
                .multiplier(2.0)
                .jitter(0.0)
                .build();

        equal(4_000L, policy.baseDelayBefore(4).toMillis(), "the last uncapped delay");
        equal(5_000L, policy.baseDelayBefore(5).toMillis(), "the first capped delay");
        equal(5_000L, policy.baseDelayBefore(40).toMillis(), "a long way past the cap");
    }

    @Test("a far-future attempt does not overflow into a negative delay")
    static void noOverflowAtHighAttemptNumbers() {
        // Multiplying into a long nanosecond count overflows around attempt
        // 40 with a multiplier of 2, and a negative delay would make the job
        // retry immediately and forever. The computation is done in double
        // and clamped for exactly this reason.
        RetryPolicy policy = plain();

        for (int attempt = 2; attempt < 2_000; attempt++) {
            Duration delay = policy.baseDelayBefore(attempt);

            isFalse(delay.isNegative(), "a negative delay at attempt " + attempt);
            isTrue(delay.compareTo(policy.maxDelay()) <= 0,
                    "the delay at attempt " + attempt + " to stay within the cap");
        }
    }

    @Test("a maxDelay below baseDelay is rejected at build time")
    static void inconsistentDelaysAreRejected() {
        IllegalArgumentException error = throws_(IllegalArgumentException.class,
                () -> RetryPolicy.builder()
                        .baseDelay(Duration.ofMinutes(1))
                        .maxDelay(Duration.ofSeconds(1))
                        .build());

        contains(error.getMessage(), "below baseDelay", "the message names the inconsistency");
    }

    @Test("a negative or null delay is rejected")
    static void badDelaysAreRejected() {
        throws_(IllegalArgumentException.class,
                () -> RetryPolicy.builder().baseDelay(Duration.ofSeconds(-1)));
        throws_(IllegalArgumentException.class, () -> RetryPolicy.builder().maxDelay(null));
    }

    @Test("a multiplier below one is rejected, since a shrinking backoff is not one")
    static void shrinkingMultiplierIsRejected() {
        throws_(IllegalArgumentException.class, () -> RetryPolicy.builder().multiplier(0.5));
    }

    @Test("NaN and infinity are rejected as multipliers")
    static void nonFiniteMultiplierIsRejected() {
        // Every comparison against NaN is false, so a plain `factor < 1.0`
        // check would let NaN through and make every delay NaN.
        throws_(IllegalArgumentException.class,
                () -> RetryPolicy.builder().multiplier(Double.NaN));
        throws_(IllegalArgumentException.class,
                () -> RetryPolicy.builder().multiplier(Double.POSITIVE_INFINITY));
    }

    // ---- jitter -----------------------------------------------------------

    @Test("jitter keeps the delay inside its proportional window")
    static void jitterStaysInItsWindow() {
        RetryPolicy policy = RetryPolicy.builder()
                .baseDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofMinutes(10))
                .multiplier(2.0)
                .jitter(0.2)
                .build();

        Random random = new Random(42);
        long base = policy.baseDelayBefore(2).toMillis();

        for (int i = 0; i < 10_000; i++) {
            long jittered = policy.delayBefore(2, random).toMillis();

            isTrue(jittered >= base * 0.8, "delay " + jittered + " to be at least 80% of " + base);
            isTrue(jittered <= base * 1.2, "delay " + jittered + " to be at most 120% of " + base);
        }
    }

    @Test("jitter actually spreads the delay rather than returning one value")
    static void jitterSpreads() {
        // The point of jitter is decorrelation, so a "jitter" that always
        // returned the same number would satisfy the bounds test above while
        // doing nothing at all.
        RetryPolicy policy = RetryPolicy.builder()
                .baseDelay(Duration.ofSeconds(10))
                .maxDelay(Duration.ofMinutes(10))
                .jitter(0.5)
                .build();

        Random random = new Random(7);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(policy.delayBefore(3, random).toMillis());
        }

        isTrue(seen.size() > 150, "200 samples to produce many distinct delays, got " + seen.size());
    }

    @Test("zero jitter returns the base delay exactly")
    static void zeroJitterIsExact() {
        Random random = new Random(1);

        equal(plain().baseDelayBefore(4), plain().delayBefore(4, random),
                "the unjittered delay");
    }

    @Test("jitter never produces a zero delay")
    static void jitterNeverCollapsesToZero() {
        // A base delay under a millisecond can round to zero once scaled,
        // which would retry with no wait at all and spin.
        RetryPolicy policy = RetryPolicy.builder()
                .baseDelay(Duration.ofMillis(1))
                .maxDelay(Duration.ofSeconds(1))
                .jitter(1.0)
                .build();

        Random random = new Random(3);
        for (int i = 0; i < 1_000; i++) {
            Duration delay = policy.delayBefore(2, random);

            isFalse(delay.isZero(), "a zero delay from a jittered one-millisecond base");
            isFalse(delay.isNegative(), "a negative delay");
        }
    }

    @Test("the first attempt has no delay however large the jitter")
    static void jitterDoesNotDelayTheFirstAttempt() {
        RetryPolicy policy = RetryPolicy.builder().jitter(1.0).build();

        isTrue(policy.delayBefore(1, new Random(5)).isZero(), "the first attempt's delay");
    }

    @Test("jitter outside zero to one is rejected")
    static void badJitterIsRejected() {
        throws_(IllegalArgumentException.class, () -> RetryPolicy.builder().jitter(-0.1));
        throws_(IllegalArgumentException.class, () -> RetryPolicy.builder().jitter(1.5));
        throws_(IllegalArgumentException.class, () -> RetryPolicy.builder().jitter(Double.NaN));
    }

    @Test("the same seed gives the same delays")
    static void jitterIsReproducibleFromItsSeed() {
        // The Random is a parameter rather than a field precisely so that a
        // test, and a replayed journal, can be deterministic.
        RetryPolicy policy = RetryPolicy.standard();

        equal(policy.delayBefore(3, new Random(99)), policy.delayBefore(3, new Random(99)),
                "two delays from the same seed");
    }

    // ---- the standard policy ----------------------------------------------

    @Test("the standard policy is four attempts from one second to one minute")
    static void standardPolicy() {
        RetryPolicy policy = RetryPolicy.standard();

        equal(4, policy.maxAttempts(), "the attempt limit");
        equal(1_000L, policy.baseDelay().toMillis(), "the base delay");
        equal(60_000L, policy.maxDelay().toMillis(), "the cap");
        near(2.0, policy.multiplier(), 0.0, "the multiplier");
        near(0.2, policy.jitter(), 0.0, "the jitter");
    }

    @Test("a policy describes itself for cadence inspect")
    static void describesItself() {
        String described = RetryPolicy.standard().toString();

        contains(described, "4 attempts", "the attempt count");
        contains(described, "jitter", "the jitter");
    }
}
