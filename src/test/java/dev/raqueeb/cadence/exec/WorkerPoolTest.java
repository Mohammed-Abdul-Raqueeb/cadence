package dev.raqueeb.cadence.exec;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The worker pool and the clock it runs alongside. */
final class WorkerPoolTest {

    /** Waits for a latch, failing the test rather than hanging if it never opens. */
    private static void await(CountDownLatch latch, String what) {
        try {
            isTrue(latch.await(5, TimeUnit.SECONDS), "to reach " + what + " within five seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + what, e);
        }
    }

    /** Waits until the pool has nothing outstanding. */
    private static void settle(WorkerPool pool) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.inFlightCount() == 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(pool.inFlightCount() + " tasks still in flight after 5s");
    }

    // ---- construction -----------------------------------------------------

    @Test("a pool runs what it is given")
    static void runsTasks() {
        // The queue is sized to hold the whole batch. A smaller one would
        // rightly refuse the overflow, which is what fullQueueRefuses covers.
        try (WorkerPool pool = new WorkerPool(2, 32, "test")) {
            AtomicInteger ran = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(20);

            for (int i = 0; i < 20; i++) {
                isTrue(pool.submit(() -> {
                    ran.incrementAndGet();
                    done.countDown();
                }), "each submission to be accepted");
            }

            await(done, "all twenty tasks");
            equal(20, ran.get(), "the number of tasks run");
            equal(20L, pool.completedCount(), "the completed count");
        }
    }

    @Test("threads are named after the pool")
    static void threadsAreNamed() {
        // The first thing anyone does with a stuck scheduler is take a thread
        // dump, and "Thread-7" says nothing about which pool it belongs to.
        try (WorkerPool pool = new WorkerPool(1, 4, "cadence-nameme")) {
            CountDownLatch seen = new CountDownLatch(1);
            StringBuilder name = new StringBuilder();

            pool.submit(() -> {
                name.append(Thread.currentThread().getName());
                seen.countDown();
            });

            await(seen, "the task");
            contains(name.toString(), "cadence-nameme", "the thread name");
        }
    }

    @Test("a pool needs at least one thread and room for one task")
    static void sizesAreValidated() {
        throws_(IllegalArgumentException.class, () -> new WorkerPool(0, 4, "test"));
        throws_(IllegalArgumentException.class, () -> new WorkerPool(-1, 4, "test"));
        throws_(IllegalArgumentException.class, () -> new WorkerPool(1, 0, "test"));
    }

    @Test("a machine-sized pool has at least two threads")
    static void machineSizedPool() {
        try (WorkerPool pool = WorkerPool.sizedForMachine("test")) {
            isTrue(pool.size() >= 2, "at least two threads, got " + pool.size());
        }
    }

    @Test("a null task is refused")
    static void nullTaskIsRefused() {
        try (WorkerPool pool = new WorkerPool(1, 4, "test")) {
            throws_(IllegalArgumentException.class, () -> pool.submit(null));
        }
    }

    // ---- the bounded queue ------------------------------------------------

    @Test("a full queue refuses rather than growing")
    static void fullQueueRefuses() {
        // The behaviour newFixedThreadPool does not offer. An unbounded queue
        // turns a backlog into an OutOfMemoryError two hours later; a
        // rejection is visible at the moment the backlog starts.
        try (WorkerPool pool = new WorkerPool(1, 2, "test")) {
            CountDownLatch block = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);

            // Occupy the single worker.
            pool.submit(() -> {
                started.countDown();
                await(block, "the release");
            });
            await(started, "the blocking task");

            // Fill the two queue slots, then overflow.
            isTrue(pool.submit(() -> {
            }), "the first queued task");
            isTrue(pool.submit(() -> {
            }), "the second queued task");
            isFalse(pool.submit(() -> {
            }), "a third task beyond the queue");

            equal(1L, pool.rejectedCount(), "the rejected count");
            block.countDown();
        }
    }

    @Test("rejections are counted, since they mean jobs that could not run")
    static void rejectionsAreCounted() {
        try (WorkerPool pool = new WorkerPool(1, 1, "test")) {
            CountDownLatch block = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            pool.submit(() -> {
                started.countDown();
                await(block, "the release");
            });
            await(started, "the blocking task");

            pool.submit(() -> {
            });
            for (int i = 0; i < 5; i++) {
                pool.submit(() -> {
                });
            }

            equal(5L, pool.rejectedCount(), "the rejected count");
            block.countDown();
        }
    }

    @Test("in-flight counts queued and running work together")
    static void inFlightCountsBoth() {
        try (WorkerPool pool = new WorkerPool(1, 4, "test")) {
            CountDownLatch block = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);

            pool.submit(() -> {
                started.countDown();
                await(block, "the release");
            });
            await(started, "the blocking task");
            pool.submit(() -> {
            });

            equal(2, pool.inFlightCount(), "one running and one queued");

            block.countDown();
            settle(pool);
            equal(0, pool.inFlightCount(), "nothing in flight once drained");
        }
    }

    @Test("a refused submission is not counted as in flight")
    static void rejectionDoesNotLeak() {
        // The counter is incremented before the offer, so a refusal has to
        // decrement it again or the count drifts up for ever.
        try (WorkerPool pool = new WorkerPool(1, 1, "test")) {
            CountDownLatch block = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            pool.submit(() -> {
                started.countDown();
                await(block, "the release");
            });
            await(started, "the blocking task");

            pool.submit(() -> {
            });
            isFalse(pool.submit(() -> {
            }), "the overflowing task");

            equal(2, pool.inFlightCount(), "only the accepted tasks");
            block.countDown();
            settle(pool);
            equal(0, pool.inFlightCount(), "nothing in flight once drained");
        }
    }

    // ---- a throwing task --------------------------------------------------

    @Test("a task that throws does not kill its worker")
    static void throwingTaskDoesNotKillTheWorker() {
        // A dying worker shrinks the pool silently, and the scheduler then
        // slows down with nothing in the logs to say why.
        try (WorkerPool pool = new WorkerPool(1, 8, "test")) {
            pool.submit(() -> {
                throw new IllegalStateException("deliberate");
            });
            settle(pool);

            CountDownLatch after = new CountDownLatch(1);
            pool.submit(after::countDown);

            await(after, "a task after the throwing one");
            equal(2L, pool.completedCount(), "both tasks counted as completed");
        }
    }

    // ---- shutdown ---------------------------------------------------------

    @Test("shutdown lets queued work finish")
    static void shutdownDrains() {
        // shutdownNow abandons queued tasks and shutdown waits for ever.
        // Draining to a deadline is what an operator restarting a scheduler
        // actually wants.
        WorkerPool pool = new WorkerPool(2, 32, "test");
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 16; i++) {
            pool.submit(ran::incrementAndGet);
        }

        isTrue(pool.shutdown(Duration.ofSeconds(5)), "a clean shutdown");
        equal(16, ran.get(), "every queued task to have run");
    }

    @Test("shutdown refuses new work")
    static void shutdownRefusesNewWork() {
        WorkerPool pool = new WorkerPool(1, 4, "test");
        pool.shutdown(Duration.ofSeconds(2));

        isFalse(pool.submit(() -> {
        }), "a submission after shutdown");
        isTrue(pool.isStopping(), "the pool to report stopping");
    }

    @Test("shutdown reports when the grace period expires")
    static void shutdownReportsTimeout() {
        WorkerPool pool = new WorkerPool(1, 4, "test");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        pool.submit(() -> {
            started.countDown();
            try {
                // Deliberately longer than the grace period below.
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        await(started, "the long task");

        isFalse(pool.shutdown(Duration.ofMillis(200)), "a clean shutdown of a stuck pool");

        release.countDown();
    }

    @Test("an idle pool shuts down promptly without being interrupted")
    static void idlePoolShutsDownPromptly() {
        // Workers poll with a timeout rather than blocking in take(), so an
        // idle worker notices the stop flag on its own.
        WorkerPool pool = new WorkerPool(4, 8, "test");

        long start = System.nanoTime();
        isTrue(pool.shutdown(Duration.ofSeconds(3)), "a clean shutdown");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        isTrue(elapsedMs < 2_000, "shutdown to take under two seconds, took " + elapsedMs + "ms");
    }

    @Test("closing twice is harmless")
    static void doubleCloseIsFine() {
        WorkerPool pool = new WorkerPool(1, 4, "test");
        pool.close();
        pool.close();
    }

    // ---- the clock --------------------------------------------------------

    @Test("a manual clock stands still until moved")
    static void manualClockStandsStill() {
        LocalDateTime start = LocalDateTime.parse("2026-10-28T08:00");
        Clock.Manual clock = Clock.manual(start);

        clock.sleep(Duration.ofHours(5));

        // A sleep that advanced time would let the scheduler's own waiting
        // change what it then observes, which is the coupling Manual exists
        // to remove.
        equal(start, clock.now(), "the time after sleeping");
        equal(Duration.ofHours(5), clock.lastSleep(), "the recorded sleep");
        equal(1, clock.sleeps(), "the sleep count");
    }

    @Test("a manual clock advances and can be set")
    static void manualClockMoves() {
        Clock.Manual clock = Clock.manual(LocalDateTime.parse("2026-10-28T08:00"));

        clock.advance(Duration.ofMinutes(90));
        equal(LocalDateTime.parse("2026-10-28T09:30"), clock.now(), "after advancing");

        // 2028 rather than 2026, which is not a leap year -- reaching a date
        // like this without waiting two years is the point of a manual clock.
        clock.set(LocalDateTime.parse("2028-02-29T00:00"));
        equal(LocalDateTime.parse("2028-02-29T00:00"), clock.now(), "after setting");
    }

    @Test("advance refuses to go backwards but set allows it")
    static void onlySetGoesBackwards() {
        // Backwards is occasionally the point -- a daylight-saving rollback
        // is real -- but it should have to be asked for by name.
        Clock.Manual clock = Clock.manual(LocalDateTime.parse("2026-10-28T08:00"));

        throws_(IllegalArgumentException.class, () -> clock.advance(Duration.ofHours(-1)));

        clock.set(LocalDateTime.parse("2026-10-28T07:00"));
        equal(LocalDateTime.parse("2026-10-28T07:00"), clock.now(), "the time after setting back");
    }

    @Test("a manual clock counts wakes")
    static void manualClockCountsWakes() {
        Clock.Manual clock = Clock.manual(LocalDateTime.parse("2026-10-28T08:00"));

        clock.wake();
        clock.wake();

        equal(2, clock.wakes(), "the wake count");
    }

    @Test("a manual clock needs a start time, and cannot be set to none")
    static void manualClockValidates() {
        throws_(IllegalArgumentException.class, () -> Clock.manual(null));
        throws_(IllegalArgumentException.class,
                () -> Clock.manual(LocalDateTime.parse("2026-10-28T08:00")).set(null));
    }

    @Test("the system clock returns promptly from a zero or negative sleep")
    static void systemClockZeroSleep() throws InterruptedException {
        Clock clock = Clock.system();

        long start = System.nanoTime();
        clock.sleep(Duration.ZERO);
        clock.sleep(Duration.ofSeconds(-1));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        isTrue(elapsedMs < 500, "a zero sleep to return at once, took " + elapsedMs + "ms");
    }

    @Test("a wake cuts short a system clock sleep")
    static void systemClockWakeInterruptsSleep() throws InterruptedException {
        Clock clock = Clock.system();
        CountDownLatch sleeping = new CountDownLatch(1);

        Thread waker = new Thread(() -> {
            await(sleeping, "the sleeper to start");
            // A short pause so the sleep is genuinely in progress rather than
            // being cut short before it begins.
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            clock.wake();
        });
        waker.setDaemon(true);
        waker.start();

        long start = System.nanoTime();
        sleeping.countDown();
        clock.sleep(Duration.ofSeconds(10));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        isTrue(elapsedMs < 5_000, "a woken sleep to end early, took " + elapsedMs + "ms");
    }

    @Test("a wake arriving just before a sleep is not lost")
    static void systemClockWakeIsNotLost() throws InterruptedException {
        // The window this guards: between "a job was submitted" and "the
        // scheduler starts waiting", a notify would go nowhere and the
        // scheduler would sleep through its own new job.
        Clock clock = Clock.system();
        clock.wake();

        long start = System.nanoTime();
        clock.sleep(Duration.ofSeconds(10));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        isTrue(elapsedMs < 1_000, "the pending wake to be honoured, took " + elapsedMs + "ms");
    }

    @Test("a system clock reads a real time in its zone")
    static void systemClockReadsTheTime() {
        LocalDateTime utc = Clock.system(java.time.ZoneId.of("UTC")).now();

        isTrue(utc.getYear() >= 2024, "a plausible year, got " + utc);
    }
}
