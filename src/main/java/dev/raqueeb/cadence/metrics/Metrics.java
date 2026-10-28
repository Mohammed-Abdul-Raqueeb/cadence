package dev.raqueeb.cadence.metrics;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters and latency histograms, by name.
 *
 * <p>Enough to answer the questions an operator actually asks of a scheduler
 * — how many jobs ran, how many failed, how long they take — and no more.
 * There is no gauge type, no tagging and no export format, because each
 * would be a guess at an integration this library does not have.
 *
 * <p>Thread-safe: workers record into it concurrently.
 */
public final class Metrics {

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();

    /**
     * The counter under a name, creating it on first use.
     *
     * @param name the counter's name, such as {@code jobs.failed}
     * @return the counter
     */
    public Counter counter(String name) {
        return counters.computeIfAbsent(requireName(name), key -> new Counter());
    }

    /**
     * The histogram under a name, creating it on first use.
     *
     * @param name the histogram's name, such as {@code jobs.duration.ms}
     * @return the histogram
     */
    public Histogram histogram(String name) {
        return histograms.computeIfAbsent(requireName(name), key -> new Histogram());
    }

    /**
     * Every counter's current value, by name.
     *
     * @return a snapshot, sorted by name
     */
    public Map<String, Long> counterValues() {
        return counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Long.valueOf(entry.getValue().value()),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
    }

    /**
     * The names of every histogram.
     *
     * @return the names, sorted
     */
    public List<String> histogramNames() {
        return histograms.keySet().stream().sorted().toList();
    }

    /** Resets every counter and histogram. */
    public void reset() {
        counters.values().forEach(Counter::reset);
        histograms.values().forEach(Histogram::reset);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a metric needs a name");
        }
        return name;
    }

    /** A monotonically increasing count. */
    public static final class Counter {

        private final AtomicLong value = new AtomicLong();

        private Counter() {
        }

        /** Adds one. */
        public void increment() {
            value.incrementAndGet();
        }

        /**
         * Adds {@code amount}.
         *
         * @param amount how much to add; must not be negative
         */
        public void add(long amount) {
            if (amount < 0) {
                // A counter that can go down is a gauge, and the two are read
                // differently: a rate computed from a counter that decreased
                // is nonsense rather than merely wrong.
                throw new IllegalArgumentException(
                        "a counter only goes up; got " + amount);
            }
            value.addAndGet(amount);
        }

        /**
         * The current value.
         *
         * @return the count
         */
        public long value() {
            return value.get();
        }

        /** Sets the count back to zero. */
        void reset() {
            value.set(0);
        }

        @Override
        public String toString() {
            return Long.toString(value());
        }
    }
}
