package dev.raqueeb.cadence.metrics;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.near;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Counters and the bucketed histogram. */
final class MetricsTest {

    // ---- counters ---------------------------------------------------------

    @Test("a counter starts at zero and counts up")
    static void counterCounts() {
        Metrics metrics = new Metrics();
        Metrics.Counter counter = metrics.counter("jobs.run");

        equal(0L, counter.value(), "a fresh counter");
        counter.increment();
        counter.increment();
        counter.add(5);

        equal(7L, counter.value(), "after two increments and five added");
    }

    @Test("the same name returns the same counter")
    static void countersAreInterned() {
        Metrics metrics = new Metrics();

        metrics.counter("jobs.run").increment();
        metrics.counter("jobs.run").increment();

        equal(2L, metrics.counter("jobs.run").value(), "the shared counter");
    }

    @Test("a counter refuses to go down")
    static void counterRefusesNegatives() {
        // A counter that can decrease is a gauge, and the two are read
        // differently: a rate computed across a decrease is nonsense rather
        // than merely wrong.
        Metrics metrics = new Metrics();

        IllegalArgumentException error = throws_(IllegalArgumentException.class,
                () -> metrics.counter("jobs.run").add(-1));
        contains(error.getMessage(), "only goes up", "the message says why");
    }

    @Test("a metric needs a name")
    static void namesAreRequired() {
        Metrics metrics = new Metrics();

        throws_(IllegalArgumentException.class, () -> metrics.counter(null));
        throws_(IllegalArgumentException.class, () -> metrics.counter("  "));
        throws_(IllegalArgumentException.class, () -> metrics.histogram(null));
    }

    @Test("counter values come out sorted by name")
    static void counterValuesAreSorted() {
        Metrics metrics = new Metrics();
        metrics.counter("zebra").increment();
        metrics.counter("aardvark").increment();
        metrics.counter("mongoose").increment();

        equal(List.of("aardvark", "mongoose", "zebra"),
                List.copyOf(metrics.counterValues().keySet()), "the counter names");
    }

    @Test("reset clears counters and histograms")
    static void resetClears() {
        Metrics metrics = new Metrics();
        metrics.counter("jobs.run").add(10);
        metrics.histogram("jobs.duration.ms").record(100);

        metrics.reset();

        equal(0L, metrics.counter("jobs.run").value(), "the counter");
        equal(0L, metrics.histogram("jobs.duration.ms").count(), "the histogram");
    }

    @Test("histogram names are listed, sorted")
    static void histogramNamesAreListed() {
        Metrics metrics = new Metrics();
        metrics.histogram("b.ms");
        metrics.histogram("a.ms");

        equal(List.of("a.ms", "b.ms"), metrics.histogramNames(), "the histogram names");
    }

    // ---- the histogram ----------------------------------------------------

    @Test("an empty histogram reports zeroes rather than dividing by zero")
    static void emptyHistogram() {
        Histogram histogram = new Metrics().histogram("empty");

        equal(0L, histogram.count(), "the count");
        equal(0L, histogram.sum(), "the sum");
        equal(0L, histogram.max(), "the max");
        equal(0L, histogram.percentile(0.99), "the p99");
        near(0.0, histogram.mean(), 0.0, "the mean");
        equal("no samples", histogram.toString(), "the description");
    }

    @Test("count, sum and max are exact")
    static void exactStatistics() {
        // The bucketing is an approximation only for percentiles. These three
        // are kept exactly, so a total duration is trustworthy.
        Histogram histogram = new Metrics().histogram("exact");
        for (long value : new long[] {5, 100, 3, 7000, 42}) {
            histogram.record(value);
        }

        equal(5L, histogram.count(), "the count");
        equal(7150L, histogram.sum(), "the sum");
        equal(7000L, histogram.max(), "the max");
        near(1430.0, histogram.mean(), 0.001, "the mean");
    }

    @Test("a percentile is the upper bound of its bucket, so it over-estimates")
    static void percentileOverEstimates() {
        // The approximation is deliberately one-sided: a reported p99 of 63
        // means "at least 99% were 63 or under", which is a claim that holds,
        // rather than a precise figure that is quietly wrong.
        Histogram histogram = new Metrics().histogram("bounds");
        for (int i = 0; i < 100; i++) {
            histogram.record(40);
        }

        // 40 falls in the bucket covering 32-63.
        equal(63L, histogram.percentile(0.5), "the median of a hundred 40s");
        isTrue(histogram.percentile(0.5) >= 40, "the percentile to be at or above the samples");
    }

    @Test("bucket bounds double, and are not off by a factor of two")
    static void bucketBoundsAreCorrect() {
        // The bug this pins: `1L << bucket - 1` parses as `1L << (bucket - 1)`
        // in Java, because - binds tighter than <<, which halves every bound
        // past the first.
        long[][] cases = {
                {0, 0}, {1, 1}, {2, 3}, {3, 3}, {4, 7}, {7, 7},
                {8, 15}, {15, 15}, {16, 31}, {100, 127}, {1000, 1023},
        };

        for (long[] testCase : cases) {
            Histogram histogram = new Metrics().histogram("bound-" + testCase[0]);
            histogram.record(testCase[0]);

            equal(testCase[1], histogram.percentile(1.0),
                    "the bucket bound for a sample of " + testCase[0]);
        }
    }

    @Test("a percentile separates a fast majority from a slow tail")
    static void percentileFindsTheTail() {
        // The question a histogram exists to answer, and the one a mean
        // cannot: ninety-nine fast jobs and one very slow one.
        Histogram histogram = new Metrics().histogram("tail");
        for (int i = 0; i < 99; i++) {
            histogram.record(5);
        }
        histogram.record(60_000);

        equal(7L, histogram.percentile(0.5), "the median, in the 4-7 bucket");
        equal(7L, histogram.percentile(0.9), "the p90, still in the fast bucket");
        equal(65_535L, histogram.percentile(1.0), "the p100, in the slow bucket");
        isTrue(histogram.mean() > 500, "the mean to be dragged by the outlier, got "
                + histogram.mean());
    }

    @Test("a percentile is the lowest bound that covers the asked-for share")
    static void percentileIsNearestRank() {
        // Nearest-rank, which is worth stating because the off-by-one is easy
        // to argue either way. For two samples, p50 asks for a value with at
        // least half of them at or below it -- and the smaller sample alone
        // is half, so p50 is its bucket, not the larger one's. p51 needs both
        // samples, so it crosses into the larger bucket.
        Histogram histogram = new Metrics().histogram("two");
        histogram.record(1);
        histogram.record(1000);

        equal(1L, histogram.median(), "the p50 of two samples");
        equal(1023L, histogram.percentile(0.51), "the p51 of two samples");
        equal(1023L, histogram.percentile(1.0), "the p100");
    }

    @Test("p0 and p100 are the first and last buckets")
    static void extremePercentiles() {
        Histogram histogram = new Metrics().histogram("extremes");
        histogram.record(1);
        histogram.record(1_000_000);

        equal(1L, histogram.percentile(0.0), "the p0");
        equal(1_048_575L, histogram.percentile(1.0), "the p100");
    }

    @Test("zero is recorded in its own bucket")
    static void zeroHasItsOwnBucket() {
        // A job that finishes within the clock's resolution is real, and
        // folding it into the 0-1 bucket would hide how many there were.
        Histogram histogram = new Metrics().histogram("zeroes");
        histogram.record(0);
        histogram.record(0);

        equal(2L, histogram.count(), "the count");
        equal(0L, histogram.percentile(1.0), "the p100 of two zeroes");
    }

    @Test("a negative observation is refused, naming the likely cause")
    static void negativeObservationIsRefused() {
        IllegalArgumentException error = throws_(IllegalArgumentException.class,
                () -> new Metrics().histogram("bad").record(-1));

        contains(error.getMessage(), "clock that went backwards", "the message names the cause");
    }

    @Test("a percentile outside zero to one is refused, NaN included")
    static void badPercentileIsRefused() {
        Histogram histogram = new Metrics().histogram("bad");

        throws_(IllegalArgumentException.class, () -> histogram.percentile(-0.1));
        throws_(IllegalArgumentException.class, () -> histogram.percentile(1.1));
        // Every comparison against NaN is false, so a plain range check would
        // let it through and return a nonsense bucket.
        throws_(IllegalArgumentException.class, () -> histogram.percentile(Double.NaN));
    }

    @Test("a very large observation does not overflow its bucket index")
    static void hugeObservationIsClamped() {
        Histogram histogram = new Metrics().histogram("huge");
        histogram.record(Long.MAX_VALUE);

        equal(1L, histogram.count(), "the count");
        equal(Long.MAX_VALUE, histogram.max(), "the max");
        isTrue(histogram.percentile(1.0) > 0, "a positive p100");
    }

    @Test("a histogram describes itself for a status line")
    static void describesItself() {
        Histogram histogram = new Metrics().histogram("described");
        histogram.record(10);
        histogram.record(20);

        String described = histogram.toString();
        contains(described, "n=2", "the sample count");
        contains(described, "p50", "the median");
        contains(described, "p99", "the p99");
    }

    // ---- concurrency ------------------------------------------------------

    @Test("counters and histograms survive concurrent recording")
    static void concurrentRecording() throws InterruptedException {
        // Workers record into this concurrently, so a lost update would mean
        // the numbers an operator reads are quietly short.
        Metrics metrics = new Metrics();
        int threads = 8;
        int perThread = 2_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    isTrue(start.await(5, TimeUnit.SECONDS), "the start signal");
                    for (int i = 0; i < perThread; i++) {
                        metrics.counter("jobs.run").increment();
                        metrics.histogram("jobs.duration.ms").record(i % 100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        start.countDown();
        isTrue(done.await(20, TimeUnit.SECONDS), "every thread to finish");

        equal((long) threads * perThread, metrics.counter("jobs.run").value(),
                "the counter after concurrent increments");
        equal((long) threads * perThread, metrics.histogram("jobs.duration.ms").count(),
                "the histogram count after concurrent records");
    }
}
