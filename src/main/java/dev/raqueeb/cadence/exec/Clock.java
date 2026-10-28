package dev.raqueeb.cadence.exec;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Where the scheduler gets the time from.
 *
 * <p>An interface rather than calls to {@link LocalDateTime#now()} for one
 * reason: a scheduler tested against the real clock has to sleep to make
 * anything happen. A test for "a daily job fires once a day" would take a
 * day, so in practice such tests get written against second-long schedules
 * with generous waits, which makes them slow and flaky at once — and they
 * still cannot reach February 29th, a month boundary, or a clock that jumps.
 *
 * <p>{@link Manual} makes all of those a method call.
 */
public interface Clock {

    /**
     * The current local date and time.
     *
     * @return now
     */
    LocalDateTime now();

    /**
     * Waits up to {@code duration}, or until {@link #wake()} interrupts the
     * wait.
     *
     * <p>The scheduler sleeps until its next job is due rather than polling,
     * so a submission arriving mid-sleep has to be able to cut it short.
     *
     * @param duration how long to wait at most
     * @throws InterruptedException when the waiting thread is interrupted
     */
    void sleep(Duration duration) throws InterruptedException;

    /** Cuts short any wait in progress. */
    void wake();

    /**
     * The system clock in the default zone.
     *
     * @return a clock reading the real time
     */
    static Clock system() {
        return new SystemClock(ZoneId.systemDefault());
    }

    /**
     * The system clock in a given zone.
     *
     * @param zone the zone to read the time in
     * @return a clock reading the real time
     */
    static Clock system(ZoneId zone) {
        return new SystemClock(zone);
    }

    /**
     * A clock that advances only when told to.
     *
     * @param start the time to start at
     * @return a controllable clock
     */
    static Manual manual(LocalDateTime start) {
        return new Manual(start);
    }

    /** The real clock, waiting on a monitor so {@link #wake()} can interrupt it. */
    final class SystemClock implements Clock {

        private final ZoneId zone;
        private final Object monitor = new Object();

        /**
         * Set when {@link #wake} is called, so a wake arriving just before a
         * sleep begins is not lost. Without it there is a window between
         * "something was submitted" and "the scheduler starts waiting" in
         * which the notify goes nowhere and the scheduler sleeps through its
         * own new job.
         */
        private boolean woken;

        private SystemClock(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public LocalDateTime now() {
            return LocalDateTime.now(zone);
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            long millis = duration.toMillis();
            if (millis <= 0) {
                return;
            }

            synchronized (monitor) {
                if (woken) {
                    woken = false;
                    return;
                }
                monitor.wait(millis);
                woken = false;
            }
        }

        @Override
        public void wake() {
            synchronized (monitor) {
                woken = true;
                monitor.notifyAll();
            }
        }
    }

    /**
     * A clock that stands still until moved.
     *
     * <p>Time advances only through {@link #advance} and {@link #set}, so a
     * test can step a scheduler across a month boundary or a leap day without
     * waiting. {@link #sleep} records what it was asked to wait for and
     * returns at once, which is also what makes a scheduler's own sleeping
     * observable.
     */
    final class Manual implements Clock {

        private LocalDateTime now;
        private Duration lastSleep = Duration.ZERO;
        private int sleeps;
        private int wakes;

        private Manual(LocalDateTime start) {
            if (start == null) {
                throw new IllegalArgumentException("a manual clock needs a start time");
            }
            this.now = start;
        }

        @Override
        public synchronized LocalDateTime now() {
            return now;
        }

        @Override
        public synchronized void sleep(Duration duration) {
            // Returns immediately and does not advance the clock. A sleep
            // that moved time would make the scheduler's own waiting change
            // what it then observes, which is exactly the coupling this
            // class exists to remove.
            lastSleep = duration;
            sleeps++;
        }

        @Override
        public synchronized void wake() {
            wakes++;
        }

        /**
         * Moves the clock forward.
         *
         * @param duration how far forward
         */
        public synchronized void advance(Duration duration) {
            if (duration.isNegative()) {
                throw new IllegalArgumentException(
                        "a clock does not go backwards; use set() if that is really meant");
            }
            now = now.plus(duration);
        }

        /**
         * Moves the clock to an exact time, in either direction.
         *
         * <p>Backwards is allowed here and not in {@link #advance}, because
         * going backwards is occasionally the point — a daylight-saving
         * rollback is a real thing a scheduler meets — but it should have to
         * be asked for by name.
         *
         * @param when the time to move to
         */
        public synchronized void set(LocalDateTime when) {
            if (when == null) {
                throw new IllegalArgumentException("a clock cannot be set to no time");
            }
            now = when;
        }

        /**
         * What the last {@link #sleep} was asked to wait for.
         *
         * @return the duration, or zero when nothing has slept
         */
        public synchronized Duration lastSleep() {
            return lastSleep;
        }

        /**
         * How many times anything has slept on this clock.
         *
         * @return the sleep count
         */
        public synchronized int sleeps() {
            return sleeps;
        }

        /**
         * How many times {@link #wake} has been called.
         *
         * @return the wake count
         */
        public synchronized int wakes() {
            return wakes;
        }
    }
}
