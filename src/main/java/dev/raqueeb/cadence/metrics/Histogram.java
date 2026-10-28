package dev.raqueeb.cadence.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A latency distribution, in fixed logarithmic buckets.
 *
 * <p>Buckets rather than stored samples, and the reason is the question being
 * asked. A mean job duration is nearly useless for a scheduler: one job that
 * hangs for an hour moves the mean of a thousand fast jobs more than it
 * deserves, while a thousand jobs each a little slower than yesterday barely
 * move it at all. The useful numbers are the high percentiles, and those need
 * a distribution.
 *
 * <p>Keeping every sample would give exact percentiles and unbounded memory —
 * a scheduler running for a month would hold every duration it ever saw.
 * Fixed buckets give bounded memory and approximate percentiles, and the
 * approximation is honest: {@link #percentile} returns the upper bound of the
 * bucket the percentile falls in, so a reported p99 of 63ms means "at least
 * 99% of samples were 63ms or under" rather than a precise figure that is
 * quietly wrong.
 *
 * <p>Buckets double. Bucket 0 holds exactly zero; bucket {@code b} holds
 * 2^(b-1) through 2^b - 1, so the bounds run 1, 3, 7, 15, 31, 63 and on up to
 * 2^41 - 1 milliseconds — about seventy years, and so beyond overflowing in
 * practice. Resolution is within a factor of two, which is the right
 * granularity for latency: the difference between 10ms and 11ms rarely
 * matters, the difference between 10ms and 10 seconds always does.
 *
 * <p>Thread-safe and lock-free.
 */
public final class Histogram {

    /** Buckets for 2^0 through 2^40 milliseconds, plus one for zero. */
    private static final int BUCKETS = 42;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong sum = new AtomicLong();
    private final AtomicLong maximum = new AtomicLong();

    Histogram() {
    }

    /**
     * Records one observation.
     *
     * @param value the measurement, in whatever unit the histogram's name says
     */
    public void record(long value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "a duration cannot be negative, got " + value
                            + "; a clock that went backwards is the usual cause");
        }

        counts.incrementAndGet(bucketFor(value));
        total.incrementAndGet();
        sum.addAndGet(value);
        maximum.accumulateAndGet(value, Math::max);
    }

    /** The bucket a value falls in: 0 for zero, else one past its highest set bit. */
    private static int bucketFor(long value) {
        if (value == 0) {
            return 0;
        }
        int bucket = 64 - Long.numberOfLeadingZeros(value);
        return Math.min(bucket, BUCKETS - 1);
    }

    /**
     * The upper bound of a bucket, inclusive.
     *
     * <p>Bucket {@code b} above zero holds values from 2^(b-1) to 2^b - 1,
     * so the bound is {@code (1 << b) - 1}. The parentheses are not
     * decoration: {@code -} binds tighter than {@code <<} in Java, so
     * {@code 1L << bucket - 1} is {@code 1L << (bucket - 1)}, which is half
     * the right answer for every bucket past the first.
     */
    private static long upperBoundOf(int bucket) {
        return bucket == 0 ? 0L : (1L << bucket) - 1;
    }

    /**
     * How many observations have been recorded.
     *
     * @return the sample count
     */
    public long count() {
        return total.get();
    }

    /**
     * The sum of every observation.
     *
     * @return the total
     */
    public long sum() {
        return sum.get();
    }

    /**
     * The largest observation.
     *
     * @return the maximum, or 0 when nothing has been recorded
     */
    public long max() {
        return maximum.get();
    }

    /**
     * The arithmetic mean.
     *
     * <p>Kept because it is cheap and occasionally wanted, not because it is
     * the right number to look at. {@link #percentile} is.
     *
     * @return the mean, or 0 when nothing has been recorded
     */
    public double mean() {
        long samples = total.get();
        return samples == 0 ? 0.0 : (double) sum.get() / samples;
    }

    /**
     * An approximate percentile.
     *
     * <p>Returns the upper bound of the bucket the percentile falls in, so
     * the answer is always an over-estimate rather than an unknown one.
     *
     * @param fraction which percentile, from 0 to 1; 0.99 for p99
     * @return the bound, or 0 when nothing has been recorded
     */
    public long percentile(double fraction) {
        if (!(fraction >= 0.0 && fraction <= 1.0)) {
            // Written to reject NaN as well, since every comparison against
            // NaN is false.
            throw new IllegalArgumentException(
                    "a percentile is a fraction between 0 and 1, got " + fraction);
        }

        long samples = total.get();
        if (samples == 0) {
            return 0L;
        }

        // Ceiling, so p50 of two samples is the second one rather than the
        // first: asking for "half the samples at or below this" must include
        // the half-way sample.
        long target = (long) Math.ceil(fraction * samples);
        if (target == 0) {
            target = 1;
        }

        long seen = 0;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            seen += counts.get(bucket);
            if (seen >= target) {
                return upperBoundOf(bucket);
            }
        }
        return maximum.get();
    }

    /**
     * The median.
     *
     * @return the approximate 50th percentile
     */
    public long median() {
        return percentile(0.5);
    }

    /** Clears every observation. */
    void reset() {
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            counts.set(bucket, 0);
        }
        total.set(0);
        sum.set(0);
        maximum.set(0);
    }

    @Override
    public String toString() {
        if (total.get() == 0) {
            return "no samples";
        }
        return String.format("n=%d p50<=%d p99<=%d max=%d",
                count(), median(), percentile(0.99), max());
    }
}
