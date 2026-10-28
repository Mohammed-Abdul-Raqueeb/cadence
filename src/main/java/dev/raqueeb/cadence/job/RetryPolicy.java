package dev.raqueeb.cadence.job;

import java.time.Duration;
import java.util.Random;

/**
 * How many times to retry a failing job, and how long to wait between tries.
 *
 * <p>The delay grows geometrically and is then jittered. Both halves matter,
 * and the jitter is the half usually left out:
 *
 * <p><b>Why it grows.</b> A job that failed because a database was
 * unreachable will fail again a second later. Retrying at a fixed interval
 * turns one outage into a steady load against the thing that is already down.
 *
 * <p><b>Why it is jittered.</b> This is the part worth being deliberate
 * about. When a shared dependency fails, every job that depends on it fails
 * at nearly the same moment, and a purely exponential backoff retries them
 * all at nearly the same moment too — forever. The retries stay synchronised
 * through every doubling, so the dependency is hit by the whole herd just as
 * it tries to come back. Spreading each delay across a window breaks that
 * correlation on the first retry and keeps it broken.
 *
 * <p>The jitter here is proportional and symmetric: a delay of 10s with 0.2
 * jitter lands uniformly in 8s-12s. Proportional rather than absolute so the
 * spread keeps up as the delay grows, since ±1s on a ten-minute wait would
 * not separate anything.
 *
 * <p>Instances are immutable.
 */
public final class RetryPolicy {

    /** Attempts, counting the first try. 1 means no retries. */
    private final int maxAttempts;

    /** The delay before the first retry, doubled (or multiplied) thereafter. */
    private final Duration baseDelay;

    /** The ceiling on a delay, applied before jitter. */
    private final Duration maxDelay;

    /** What each successive delay is multiplied by. */
    private final double multiplier;

    /** The proportional jitter, from 0 (none) to 1 (±100%). */
    private final double jitter;

    private RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay,
            double multiplier, double jitter) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.jitter = jitter;
    }

    /**
     * A policy that never retries.
     *
     * @return a policy with a single attempt
     */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0, 0.0);
    }

    /**
     * The default policy: four attempts, one second doubling to one minute,
     * jittered by a fifth.
     *
     * @return a sensible policy for a job that talks to something over a
     *     network
     */
    public static RetryPolicy standard() {
        return builder()
                .maxAttempts(4)
                .baseDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofMinutes(1))
                .multiplier(2.0)
                .jitter(0.2)
                .build();
    }

    /**
     * Starts building a policy.
     *
     * @return a builder pre-set to the standard values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The delay before attempt number {@code attempt}, without jitter.
     *
     * <p>Exposed separately from {@link #delayBefore(int, Random)} because it
     * is the part a test can assert exactly, and because an operator reading
     * {@code cadence inspect} wants the schedule rather than one sample of it.
     *
     * @param attempt the attempt about to be made, counting from 1
     * @return the delay, or zero for the first attempt
     */
    public Duration baseDelayBefore(int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }

        // Computed in double and clamped, rather than multiplied into a long.
        // A multiplier of 2 over 40 attempts overflows a long nanosecond
        // count, and an overflowed delay becomes a negative one, which would
        // make a job retry immediately and forever.
        double millis = baseDelay.toMillis() * Math.pow(multiplier, attempt - 2.0);
        double capped = Math.min(millis, maxDelay.toMillis());

        return Duration.ofMillis((long) capped);
    }

    /**
     * The delay before attempt number {@code attempt}, jittered.
     *
     * @param attempt the attempt about to be made, counting from 1
     * @param random  the source of jitter, passed in so a test is deterministic
     * @return the delay to wait, never negative
     */
    public Duration delayBefore(int attempt, Random random) {
        Duration base = baseDelayBefore(attempt);
        if (jitter == 0.0 || base.isZero()) {
            return base;
        }

        // Uniform in [1 - jitter, 1 + jitter]. nextDouble() is in [0, 1), so
        // the factor reaches the bottom of the window but not quite the top;
        // for spreading a herd that asymmetry does not matter.
        double factor = 1.0 - jitter + random.nextDouble() * 2.0 * jitter;
        long millis = (long) (base.toMillis() * factor);

        // A base delay under a millisecond can round to zero here, which would
        // retry with no wait at all. One millisecond is the floor.
        return Duration.ofMillis(Math.max(1L, millis));
    }

    /**
     * Whether another attempt is allowed after {@code attemptsMade} have been
     * made.
     *
     * @param attemptsMade how many attempts have already run
     * @return true when the job may be retried
     */
    public boolean shouldRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /**
     * The total number of attempts allowed, counting the first.
     *
     * @return the attempt limit
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * The delay before the first retry.
     *
     * @return the base delay
     */
    public Duration baseDelay() {
        return baseDelay;
    }

    /**
     * The ceiling on a delay before jitter.
     *
     * @return the maximum delay
     */
    public Duration maxDelay() {
        return maxDelay;
    }

    /**
     * The geometric growth factor.
     *
     * @return the multiplier
     */
    public double multiplier() {
        return multiplier;
    }

    /**
     * The proportional jitter.
     *
     * @return the jitter, from 0 to 1
     */
    public double jitter() {
        return jitter;
    }

    @Override
    public String toString() {
        if (maxAttempts == 1) {
            return "no retries";
        }
        return maxAttempts + " attempts, " + baseDelay.toMillis() + "ms x" + multiplier
                + " capped at " + maxDelay.toMillis() + "ms, jitter " + jitter;
    }

    /** Builds a {@link RetryPolicy}, validating as it goes. */
    public static final class Builder {

        private int maxAttempts = 4;
        private Duration baseDelay = Duration.ofSeconds(1);
        private Duration maxDelay = Duration.ofMinutes(1);
        private double multiplier = 2.0;
        private double jitter = 0.2;

        private Builder() {
        }

        /**
         * Sets the attempt limit, counting the first try.
         *
         * @param attempts at least 1
         * @return this builder
         */
        public Builder maxAttempts(int attempts) {
            if (attempts < 1) {
                throw new IllegalArgumentException(
                        "maxAttempts counts the first try, so it is at least 1, got " + attempts);
            }
            this.maxAttempts = attempts;
            return this;
        }

        /**
         * Sets the delay before the first retry.
         *
         * @param delay a non-negative duration
         * @return this builder
         */
        public Builder baseDelay(Duration delay) {
            requireNonNegative(delay, "baseDelay");
            this.baseDelay = delay;
            return this;
        }

        /**
         * Sets the ceiling on a delay.
         *
         * @param delay a non-negative duration
         * @return this builder
         */
        public Builder maxDelay(Duration delay) {
            requireNonNegative(delay, "maxDelay");
            this.maxDelay = delay;
            return this;
        }

        /**
         * Sets the geometric growth factor.
         *
         * @param factor at least 1; 1 means a constant delay
         * @return this builder
         */
        public Builder multiplier(double factor) {
            if (!(factor >= 1.0) || Double.isInfinite(factor)) {
                // Written as !(x >= 1.0) rather than x < 1.0 so that NaN is
                // rejected too; every comparison against NaN is false.
                throw new IllegalArgumentException(
                        "multiplier must be at least 1, since a shrinking backoff is not a"
                                + " backoff, got " + factor);
            }
            this.multiplier = factor;
            return this;
        }

        /**
         * Sets the proportional jitter.
         *
         * @param fraction from 0 for none to 1 for plus or minus the whole delay
         * @return this builder
         */
        public Builder jitter(double fraction) {
            if (!(fraction >= 0.0 && fraction <= 1.0)) {
                throw new IllegalArgumentException(
                        "jitter is a fraction of the delay, so it is between 0 and 1, got "
                                + fraction);
            }
            this.jitter = fraction;
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return the immutable policy
         */
        public RetryPolicy build() {
            if (maxDelay.compareTo(baseDelay) < 0) {
                throw new IllegalArgumentException("maxDelay (" + maxDelay.toMillis()
                        + "ms) is below baseDelay (" + baseDelay.toMillis()
                        + "ms), so the first retry would already be capped");
            }
            return new RetryPolicy(maxAttempts, baseDelay, maxDelay, multiplier, jitter);
        }

        private static void requireNonNegative(Duration delay, String name) {
            if (delay == null) {
                throw new IllegalArgumentException(name + " cannot be null");
            }
            if (delay.isNegative()) {
                throw new IllegalArgumentException(name + " cannot be negative, got " + delay);
            }
        }
    }
}
