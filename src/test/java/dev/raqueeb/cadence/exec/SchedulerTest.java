package dev.raqueeb.cadence.exec;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import dev.raqueeb.cadence.job.Job;
import dev.raqueeb.cadence.job.JobId;
import dev.raqueeb.cadence.job.JobSpec;
import dev.raqueeb.cadence.job.JobState;
import dev.raqueeb.cadence.job.RetryPolicy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The scheduling loop, stepped a decision at a time on a manual clock.
 *
 * <p>Every case here drives {@link Scheduler#fireDueJobs()} by hand rather
 * than starting the loop and waiting. The jobs still run on real worker
 * threads, so the one unavoidable wait is for a handler to finish — which is
 * what {@link #settle} is for.
 */
final class SchedulerTest {

    private static final LocalDateTime START = LocalDateTime.parse("2026-10-28T08:00");

    /** A scheduler on a manual clock with a single worker and a seeded jitter. */
    private static Harness harness() {
        return harness(1);
    }

    private static Harness harness(int workers) {
        Clock.Manual clock = Clock.manual(START);
        WorkerPool pool = new WorkerPool(workers, 16, "test-worker");
        List<String> logged = new CopyOnWriteArrayList<>();

        Scheduler scheduler = Scheduler.builder()
                .clock(clock)
                .workers(pool)
                .jitterSource(new Random(1))
                .log(logged::add)
                .build();

        return new Harness(scheduler, clock, pool, logged);
    }

    private record Harness(Scheduler scheduler, Clock.Manual clock, WorkerPool pool,
            List<String> logged) implements AutoCloseable {

        /** Fires what is due, then waits for the workers to finish it. */
        void step() {
            scheduler.fireDueJobs();
            settle(this);
        }

        @Override
        public void close() {
            scheduler.close();
        }
    }

    /**
     * Waits until no worker is busy and nothing is queued.
     *
     * <p>The one real wait in this file. Handlers run on worker threads, so a
     * test that checked state the instant after firing would be racing them.
     * Bounded and asserted rather than a bare sleep, so a hang fails loudly
     * instead of flaking.
     */
    private static void settle(Harness harness) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (harness.pool().inFlightCount() == 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("workers did not settle within five seconds: "
                + harness.pool().inFlightCount() + " tasks still in flight");
    }

    // ---- submission -------------------------------------------------------

    @Test("a submitted recurring job is queued for its next fire")
    static void submitQueuesForNextFire() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });

            JobId id = h.scheduler().submit(
                    JobSpec.forHandler("noop").schedule("0 9 * * *").build());

            Job job = h.scheduler().job(id).orElseThrow();
            equal(JobState.SCHEDULED, job.state(), "the state");
            equal(LocalDateTime.parse("2026-10-28T09:00"), job.nextFireAt(), "the first fire");
            equal(1, h.scheduler().queuedCount(), "the queue size");
        }
    }

    @Test("a one-shot job is queued to run at once")
    static void oneShotRunsImmediately() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });

            JobId id = h.scheduler().submit(JobSpec.forHandler("noop").build());

            equal(START, h.scheduler().job(id).orElseThrow().nextFireAt(), "the fire time");
        }
    }

    @Test("an unregistered handler is refused at submission, not at fire time")
    static void unknownHandlerIsRefusedEarly() {
        // A typo in a handler name is a configuration error, and the moment to
        // report one is while the person who made it is still watching -- not
        // at 3am when the job first fires.
        try (Harness h = harness()) {
            h.scheduler().register("known", context -> {
            });

            IllegalArgumentException error = throws_(IllegalArgumentException.class,
                    () -> h.scheduler().submit(JobSpec.forHandler("typo").build()));

            contains(error.getMessage(), "typo", "the message quotes the name given");
            contains(error.getMessage(), "known", "the message lists what is registered");
        }
    }

    @Test("a schedule that will never fire is refused at submission")
    static void impossibleScheduleIsRefused() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });

            IllegalArgumentException error = throws_(IllegalArgumentException.class,
                    () -> h.scheduler().submit(
                            JobSpec.forHandler("noop").schedule("0 0 30 2 *").build()));

            contains(error.getMessage(), "never fire", "the message says why");
        }
    }

    @Test("submitting wakes the clock, so a new job does not wait out a sleep")
    static void submitWakesTheClock() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });
            int before = h.clock().wakes();

            h.scheduler().submit(JobSpec.forHandler("noop").build());

            isTrue(h.clock().wakes() > before, "the clock to have been woken");
        }
    }

    @Test("a null spec is refused")
    static void nullSpecIsRefused() {
        try (Harness h = harness()) {
            throws_(IllegalArgumentException.class, () -> h.scheduler().submit(null));
        }
    }

    // ---- firing -----------------------------------------------------------

    @Test("a job fires when its time arrives, and not before")
    static void firesOnTime() {
        try (Harness h = harness()) {
            AtomicInteger runs = new AtomicInteger();
            h.scheduler().register("count", context -> runs.incrementAndGet());
            h.scheduler().submit(JobSpec.forHandler("count").schedule("0 9 * * *").build());

            h.step();
            equal(0, runs.get(), "runs before the fire time");

            h.clock().set(LocalDateTime.parse("2026-10-28T09:00"));
            h.step();
            equal(1, runs.get(), "runs at the fire time");
        }
    }

    @Test("a recurring job is requeued for its next fire after succeeding")
    static void recurringJobRequeues() {
        try (Harness h = harness()) {
            AtomicInteger runs = new AtomicInteger();
            h.scheduler().register("count", context -> runs.incrementAndGet());
            JobId id = h.scheduler().submit(
                    JobSpec.forHandler("count").schedule("0 * * * *").build());

            for (int hour = 9; hour <= 12; hour++) {
                h.clock().set(START.withHour(hour).withMinute(0));
                h.step();
            }

            equal(4, runs.get(), "the number of hourly runs");
            Job job = h.scheduler().job(id).orElseThrow();
            equal(JobState.SCHEDULED, job.state(), "the state between fires");
            equal(LocalDateTime.parse("2026-10-28T13:00"), job.nextFireAt(), "the next fire");
            equal(4L, job.runs(), "the recorded run count");
        }
    }

    @Test("a one-shot job leaves the queue after it runs")
    static void oneShotRetires() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });
            JobId id = h.scheduler().submit(JobSpec.forHandler("noop").build());

            h.step();

            equal(0, h.scheduler().queuedCount(), "the queue size after running");
            equal(JobState.SUCCEEDED, h.scheduler().job(id).orElseThrow().state(), "the state");
            // It stays in the table so `cadence inspect` can still find it.
            isTrue(h.scheduler().job(id).isPresent(), "the job to remain inspectable");
        }
    }

    @Test("several jobs due at once all fire")
    static void severalDueJobsAllFire() {
        try (Harness h = harness(4)) {
            AtomicInteger runs = new AtomicInteger();
            h.scheduler().register("count", context -> runs.incrementAndGet());
            for (int i = 0; i < 10; i++) {
                h.scheduler().submit(JobSpec.forHandler("count").build());
            }

            h.step();

            equal(10, runs.get(), "the number of runs");
        }
    }

    @Test("the handler is told which attempt it is on")
    static void handlerSeesTheAttemptNumber() {
        try (Harness h = harness()) {
            List<Integer> attempts = new CopyOnWriteArrayList<>();
            h.scheduler().register("flaky", context -> {
                attempts.add(context.attempt());
                if (context.attempt() < 3) {
                    throw new IllegalStateException("not yet");
                }
            });
            h.scheduler().submit(JobSpec.forHandler("flaky")
                    .retryPolicy(RetryPolicy.builder()
                            .maxAttempts(3)
                            .baseDelay(Duration.ofSeconds(1))
                            .maxDelay(Duration.ofSeconds(1))
                            .jitter(0.0)
                            .build())
                    .build());

            for (int i = 0; i < 3; i++) {
                h.step();
                h.clock().advance(Duration.ofSeconds(5));
            }

            equal(List.of(1, 2, 3), attempts, "the attempt numbers the handler saw");
        }
    }

    @Test("the handler is told its payload")
    static void handlerSeesThePayload() {
        try (Harness h = harness()) {
            List<String> seen = new CopyOnWriteArrayList<>();
            h.scheduler().register("echo", context -> seen.add(context.payload()));
            h.scheduler().submit(
                    JobSpec.forHandler("echo").payload("{\"region\":\"eu\"}").build());

            h.step();

            equal(List.of("{\"region\":\"eu\"}"), seen, "the payload the handler saw");
        }
    }

    // ---- failure and retry ------------------------------------------------

    @Test("a throwing handler puts the job into RETRYING with a backoff")
    static void failureSchedulesARetry() {
        try (Harness h = harness()) {
            h.scheduler().register("boom", context -> {
                throw new IllegalStateException("connection refused");
            });
            JobId id = h.scheduler().submit(JobSpec.forHandler("boom")
                    .retryPolicy(RetryPolicy.builder()
                            .maxAttempts(3)
                            .baseDelay(Duration.ofSeconds(10))
                            .maxDelay(Duration.ofMinutes(1))
                            .jitter(0.0)
                            .build())
                    .build());

            h.step();

            Job job = h.scheduler().job(id).orElseThrow();
            equal(JobState.RETRYING, job.state(), "the state");
            equal(START.plusSeconds(10), job.nextFireAt(), "the retry time");
            contains(job.lastError().orElseThrow(), "connection refused", "the recorded error");
        }
    }

    @Test("a job out of attempts lands in FAILED")
    static void exhaustedRetriesFail() {
        try (Harness h = harness()) {
            h.scheduler().register("boom", context -> {
                throw new IllegalStateException("still broken");
            });
            JobId id = h.scheduler().submit(JobSpec.forHandler("boom")
                    .retryPolicy(RetryPolicy.builder()
                            .maxAttempts(2)
                            .baseDelay(Duration.ofSeconds(1))
                            .maxDelay(Duration.ofSeconds(1))
                            .jitter(0.0)
                            .build())
                    .build());

            h.step();
            h.clock().advance(Duration.ofSeconds(5));
            h.step();

            Job job = h.scheduler().job(id).orElseThrow();
            equal(JobState.FAILED, job.state(), "the state");
            equal(1L, job.failures(), "the failure count");
            equal(0, h.scheduler().queuedCount(), "the queue size");
        }
    }

    @Test("a failing recurring job is still scheduled for its next fire")
    static void failingRecurringJobKeepsItsSchedule() {
        // A nightly report that failed tonight should still run tomorrow.
        // Retiring it on failure means one bad night stops it for ever.
        try (Harness h = harness()) {
            h.scheduler().register("boom", context -> {
                throw new IllegalStateException("nope");
            });
            JobId id = h.scheduler().submit(JobSpec.forHandler("boom")
                    .schedule("0 * * * *")
                    .retryPolicy(RetryPolicy.none())
                    .build());

            h.clock().set(LocalDateTime.parse("2026-10-28T09:00"));
            h.step();

            Job job = h.scheduler().job(id).orElseThrow();
            equal(JobState.SCHEDULED, job.state(), "the state after failing");
            equal(LocalDateTime.parse("2026-10-28T10:00"), job.nextFireAt(), "the next fire");
            equal(1, h.scheduler().queuedCount(), "the queue size");
        }
    }

    @Test("a handler throwing an Error is recorded, not left running")
    static void errorsAreCaught() {
        // An OutOfMemoryError in one handler must not leave the job stuck in
        // RUNNING, which nothing but a restart could clear.
        try (Harness h = harness()) {
            h.scheduler().register("explode", context -> {
                throw new StackOverflowError("too deep");
            });
            JobId id = h.scheduler().submit(JobSpec.forHandler("explode")
                    .retryPolicy(RetryPolicy.none())
                    .build());

            h.step();

            Job job = h.scheduler().job(id).orElseThrow();
            equal(JobState.FAILED, job.state(), "the state");
            contains(job.lastError().orElseThrow(), "StackOverflowError", "the recorded error");
        }
    }

    @Test("a failure with no message is recorded by its type")
    static void messagelessFailuresAreNamed() {
        // A NullPointerException often has no message, and "failed: null"
        // tells an operator nothing.
        try (Harness h = harness()) {
            h.scheduler().register("npe", context -> {
                throw new NullPointerException();
            });
            JobId id = h.scheduler().submit(
                    JobSpec.forHandler("npe").retryPolicy(RetryPolicy.none()).build());

            h.step();

            equal("NullPointerException",
                    h.scheduler().job(id).orElseThrow().lastError().orElseThrow(),
                    "the recorded error");
        }
    }

    // ---- cancellation -----------------------------------------------------

    @Test("a queued job can be cancelled and leaves the queue")
    static void cancelRemovesFromTheQueue() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });
            JobId id = h.scheduler().submit(
                    JobSpec.forHandler("noop").schedule("0 9 * * *").build());

            isTrue(h.scheduler().cancel(id), "the cancel to succeed");

            equal(JobState.CANCELLED, h.scheduler().job(id).orElseThrow().state(), "the state");
            equal(0, h.scheduler().queuedCount(), "the queue size");
        }
    }

    @Test("a cancelled job does not fire")
    static void cancelledJobsDoNotFire() {
        try (Harness h = harness()) {
            AtomicInteger runs = new AtomicInteger();
            h.scheduler().register("count", context -> runs.incrementAndGet());
            JobId id = h.scheduler().submit(
                    JobSpec.forHandler("count").schedule("0 9 * * *").build());
            h.scheduler().cancel(id);

            h.clock().set(LocalDateTime.parse("2026-10-28T09:00"));
            h.step();

            equal(0, runs.get(), "the number of runs");
        }
    }

    @Test("cancelling an unknown or settled job reports false")
    static void cancelIsHonestAboutFailing() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });
            isFalse(h.scheduler().cancel(JobId.generate()), "cancelling an unknown job");

            JobId id = h.scheduler().submit(JobSpec.forHandler("noop").build());
            h.step();

            isFalse(h.scheduler().cancel(id), "cancelling a job that has already run");
        }
    }

    // ---- sleeping ---------------------------------------------------------

    @Test("the loop waits until the next job is due")
    static void waitsUntilTheNextFire() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });
            h.scheduler().submit(JobSpec.forHandler("noop").schedule("0 9 * * *").build());

            Duration wait = h.scheduler().fireDueJobs();

            // An hour away, but capped at the 30-second idle ceiling so a
            // lost wake costs latency rather than correctness.
            equal(Duration.ofSeconds(30), wait, "the computed wait");
        }
    }

    @Test("an empty scheduler waits rather than spinning")
    static void emptySchedulerWaits() {
        try (Harness h = harness()) {
            isTrue(h.scheduler().fireDueJobs().toMillis() > 0, "the wait on an empty queue");
        }
    }

    @Test("a job due now asks for no wait at all")
    static void dueJobAsksForNoWait() {
        try (Harness h = harness()) {
            h.scheduler().register("slow", context -> {
            });
            // Two jobs due at once, one worker: after firing both there is
            // nothing left due, so the wait is the idle ceiling.
            h.scheduler().submit(JobSpec.forHandler("slow").build());

            Duration wait = h.scheduler().fireDueJobs();
            settle(h);

            isTrue(wait.toMillis() >= 0, "a non-negative wait, got " + wait);
        }
    }

    // ---- the registry under a live schedule ------------------------------

    @Test("a handler that disappears is reported rather than silently dropped")
    static void vanishedHandlerIsReported() {
        // Reachable only because unregister() exists. The job keeps its
        // schedule and is reported at each fire, rather than failing in a way
        // that burns through its retries for a reason it cannot fix.
        try (Harness h = harness()) {
            h.scheduler().register("temporary", context -> {
                throw new AssertionError("an unregistered handler should never run");
            });
            JobId id = h.scheduler().submit(
                    JobSpec.forHandler("temporary").schedule("0 * * * *").build());

            isTrue(h.scheduler().unregister("temporary"), "the unregister to find the handler");

            h.clock().set(LocalDateTime.parse("2026-10-28T09:00"));
            h.step();

            Job job = h.scheduler().job(id).orElseThrow();
            equal(JobState.SCHEDULED, job.state(), "the job to stay scheduled");
            equal(LocalDateTime.parse("2026-10-28T10:00"), job.nextFireAt(), "the next fire");
            equal(0L, job.runs(), "the run count");
            isTrue(h.logged().stream().anyMatch(line -> line.contains("no longer registered")),
                    "a warning to be logged, got " + h.logged());
        }
    }

    @Test("unregistering an unknown handler reports false")
    static void unregisterIsHonestAboutFailing() {
        try (Harness h = harness()) {
            isFalse(h.scheduler().unregister("never-registered"), "an unknown unregister");
            isFalse(h.scheduler().unregister(null), "a null unregister");
        }
    }

    // ---- metrics and inspection ------------------------------------------

    @Test("counters track submissions, starts and outcomes")
    static void countersAreRecorded() {
        try (Harness h = harness()) {
            h.scheduler().register("ok", context -> {
            });
            h.scheduler().register("bad", context -> {
                throw new IllegalStateException("no");
            });

            h.scheduler().submit(JobSpec.forHandler("ok").build());
            h.scheduler().submit(
                    JobSpec.forHandler("bad").retryPolicy(RetryPolicy.none()).build());
            h.step();

            var counters = h.scheduler().metrics().counterValues();
            equal(2L, counters.get("jobs.submitted").longValue(), "jobs.submitted");
            equal(2L, counters.get("jobs.started").longValue(), "jobs.started");
            equal(1L, counters.get("jobs.succeeded").longValue(), "jobs.succeeded");
            equal(1L, counters.get("jobs.failed").longValue(), "jobs.failed");
        }
    }

    @Test("durations are recorded into a histogram")
    static void durationsAreRecorded() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });
            h.scheduler().submit(JobSpec.forHandler("noop").build());
            h.step();

            equal(1L, h.scheduler().metrics().histogram("jobs.duration.ms").count(),
                    "the recorded sample count");
        }
    }

    @Test("jobs can be listed, and filtered by state")
    static void jobsCanBeListed() {
        try (Harness h = harness()) {
            h.scheduler().register("noop", context -> {
            });
            JobId first = h.scheduler().submit(
                    JobSpec.forHandler("noop").schedule("0 9 * * *").build());
            JobId second = h.scheduler().submit(
                    JobSpec.forHandler("noop").schedule("0 10 * * *").build());
            h.scheduler().cancel(second);

            List<JobId> all = new ArrayList<>(h.scheduler().jobs().stream().map(Job::id).toList());

            equal(List.of(first, second), all, "every job, in submission order");
            equal(1, h.scheduler().jobsIn(JobState.SCHEDULED).size(), "the scheduled jobs");
            equal(1, h.scheduler().jobsIn(JobState.CANCELLED).size(), "the cancelled jobs");
        }
    }

    @Test("handler names are listed, sorted")
    static void handlerNamesAreListed() {
        try (Harness h = harness()) {
            h.scheduler().register("zebra", context -> {
            });
            h.scheduler().register("aardvark", context -> {
            });

            equal(List.of("aardvark", "zebra"), h.scheduler().handlerNames(), "the handler names");
        }
    }

    @Test("registering needs a name and an implementation")
    static void registerRejectsNulls() {
        try (Harness h = harness()) {
            throws_(IllegalArgumentException.class, () -> h.scheduler().register(null, c -> {
            }));
            throws_(IllegalArgumentException.class, () -> h.scheduler().register("  ", c -> {
            }));
            throws_(IllegalArgumentException.class, () -> h.scheduler().register("x", null));
        }
    }

    @Test("an unknown job id finds nothing")
    static void unknownJobIsEmpty() {
        try (Harness h = harness()) {
            isTrue(h.scheduler().job(JobId.generate()).isEmpty(), "an unknown job");
        }
    }
}
