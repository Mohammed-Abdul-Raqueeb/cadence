package dev.raqueeb.cadence.job;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import java.time.Duration;
import java.time.LocalDateTime;

/** A job's specification and its progress through a fire. */
final class JobTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-28T09:00");

    private static Job job(RetryPolicy policy) {
        JobSpec spec = JobSpec.forHandler("report")
                .name("nightly report")
                .schedule("@daily")
                .retryPolicy(policy)
                .build();
        return new Job(JobId.generate(), spec, NOW, NOW.plusHours(15));
    }

    private static Job job() {
        return job(RetryPolicy.standard());
    }

    // ---- the spec ---------------------------------------------------------

    @Test("a spec defaults its name to the handler name")
    static void nameDefaultsToHandler() {
        JobSpec spec = JobSpec.forHandler("vacuum").build();

        equal("vacuum", spec.name(), "the default name");
        equal("vacuum", spec.handler(), "the handler");
    }

    @Test("a spec without a schedule is one-shot")
    static void withoutScheduleIsOneShot() {
        JobSpec spec = JobSpec.forHandler("vacuum").build();

        isFalse(spec.isRecurring(), "a job with no schedule to recur");
        isTrue(spec.schedule().isEmpty(), "the schedule to be present");
        contains(spec.toString(), "one-shot", "the description to say one-shot");
    }

    @Test("a spec with a schedule recurs")
    static void withScheduleRecurs() {
        JobSpec spec = JobSpec.forHandler("vacuum").schedule("@hourly").build();

        isTrue(spec.isRecurring(), "a scheduled job to recur");
        equal("@hourly", spec.schedule().orElseThrow().expression(), "the schedule");
    }

    @Test("a blank handler or name is rejected")
    static void blankNamesAreRejected() {
        throws_(IllegalArgumentException.class, () -> JobSpec.forHandler(null));
        throws_(IllegalArgumentException.class, () -> JobSpec.forHandler("  "));
        throws_(IllegalArgumentException.class, () -> JobSpec.forHandler("vacuum").name(""));
    }

    @Test("a null payload becomes the empty string")
    static void nullPayloadIsEmpty() {
        // A handler should not have to null-check what the scheduler hands it.
        equal("", JobSpec.forHandler("vacuum").payload(null).build().payload(), "the payload");
    }

    @Test("a null retry policy is rejected, pointing at RetryPolicy.none()")
    static void nullPolicyIsRejected() {
        IllegalArgumentException error = throws_(IllegalArgumentException.class,
                () -> JobSpec.forHandler("vacuum").retryPolicy(null));

        contains(error.getMessage(), "RetryPolicy.none()", "the message names the alternative");
    }

    @Test("a non-positive timeout is rejected")
    static void badTimeoutIsRejected() {
        throws_(IllegalArgumentException.class,
                () -> JobSpec.forHandler("vacuum").timeout(Duration.ZERO));
        throws_(IllegalArgumentException.class,
                () -> JobSpec.forHandler("vacuum").timeout(Duration.ofSeconds(-1)));
    }

    // ---- construction -----------------------------------------------------

    @Test("a new job is scheduled")
    static void newJobIsScheduled() {
        Job job = job();

        equal(JobState.SCHEDULED, job.state(), "the initial state");
        equal(0, job.attempts(), "the initial attempt count");
        equal(0L, job.runs(), "the initial run count");
        isTrue(job.lastError().isEmpty(), "an error on a job that has not run");
        isTrue(job.lastDuration().isEmpty(), "a duration on a job that has not run");
    }

    @Test("a job needs all four of its constructor arguments")
    static void constructorRejectsNulls() {
        JobSpec spec = JobSpec.forHandler("vacuum").build();

        throws_(IllegalArgumentException.class, () -> new Job(null, spec, NOW, NOW));
        throws_(IllegalArgumentException.class, () -> new Job(JobId.generate(), null, NOW, NOW));
        throws_(IllegalArgumentException.class, () -> new Job(JobId.generate(), spec, null, NOW));
        throws_(IllegalArgumentException.class, () -> new Job(JobId.generate(), spec, NOW, null));
    }

    // ---- a successful fire ------------------------------------------------

    @Test("a successful fire counts a run and clears the attempt count")
    static void successfulFire() {
        Job job = job();

        job.startAttempt(NOW);
        equal(JobState.RUNNING, job.state(), "the state while running");
        equal(1, job.attempts(), "the attempt count while running");

        job.succeed(NOW.plusSeconds(3));
        equal(JobState.SUCCEEDED, job.state(), "the state after succeeding");
        equal(1L, job.runs(), "the run count");
        equal(0L, job.failures(), "the failure count");
        equal(0, job.attempts(), "the attempt count after settling");
        equal(Duration.ofSeconds(3), job.lastDuration().orElseThrow(), "the recorded duration");
    }

    @Test("a success clears the error from a previous failure")
    static void successClearsTheError() {
        // Otherwise `cadence inspect` shows a stale error against a job that
        // is now healthy, which is worse than showing none.
        Job job = job();

        job.startAttempt(NOW);
        job.fail(NOW.plusSeconds(1), "connection refused");
        job.startAttempt(NOW.plusSeconds(2));
        job.succeed(NOW.plusSeconds(3));

        isTrue(job.lastError().isEmpty(), "the error after a later success");
    }

    // ---- a failing fire ---------------------------------------------------

    @Test("a failure with attempts left goes to RETRYING and is not yet a run")
    static void failureWithAttemptsLeft() {
        Job job = job(RetryPolicy.builder().maxAttempts(3).build());

        job.startAttempt(NOW);
        boolean retrying = job.fail(NOW.plusSeconds(1), "timed out");

        isTrue(retrying, "the job to report it will retry");
        equal(JobState.RETRYING, job.state(), "the state");
        equal(0L, job.runs(), "the run count mid-retry");
        equal(1, job.attempts(), "the attempt count carried into the retry");
        equal("timed out", job.lastError().orElseThrow(), "the recorded error");
    }

    @Test("a failure out of attempts goes to FAILED and counts a run")
    static void failureOutOfAttempts() {
        Job job = job(RetryPolicy.builder().maxAttempts(2).build());

        job.startAttempt(NOW);
        job.fail(NOW.plusSeconds(1), "first");
        job.startAttempt(NOW.plusSeconds(2));
        boolean retrying = job.fail(NOW.plusSeconds(3), "second");

        isFalse(retrying, "the job to report another retry");
        equal(JobState.FAILED, job.state(), "the state");
        equal(1L, job.runs(), "the run count");
        equal(1L, job.failures(), "the failure count");
        equal(0, job.attempts(), "the attempt count after settling");
    }

    @Test("a no-retry policy fails on the first attempt")
    static void noRetryFailsImmediately() {
        Job job = job(RetryPolicy.none());

        job.startAttempt(NOW);

        isFalse(job.fail(NOW.plusSeconds(1), "nope"), "a retry under a no-retry policy");
        equal(JobState.FAILED, job.state(), "the state");
    }

    @Test("a run is counted once however many attempts it took")
    static void attemptsAreNotRuns() {
        // The distinction an operator cares about: four attempts of one
        // nightly job is one run, not four.
        Job job = job(RetryPolicy.builder().maxAttempts(4).build());

        for (int attempt = 1; attempt <= 3; attempt++) {
            job.startAttempt(NOW);
            isTrue(job.fail(NOW.plusSeconds(attempt), "again"), "a retry at attempt " + attempt);
        }
        job.startAttempt(NOW);
        job.succeed(NOW.plusSeconds(5));

        equal(1L, job.runs(), "the run count after four attempts");
        equal(0L, job.failures(), "the failure count after an eventual success");
    }

    // ---- illegal transitions ---------------------------------------------

    @Test("an illegal transition throws and names what was allowed")
    static void illegalTransitionThrows() {
        // Thrown rather than ignored: a silently-dropped transition leaves a
        // job in a state nothing will move it out of, which looks like a hang
        // and is much harder to diagnose than an exception.
        Job job = job();

        IllegalStateException error =
                throws_(IllegalStateException.class, () -> job.moveTo(JobState.SUCCEEDED));

        contains(error.getMessage(), "SCHEDULED", "the message names the current state");
        contains(error.getMessage(), "SUCCEEDED", "the message names the attempted state");
        contains(error.getMessage(), "RUNNING", "the message lists what is allowed");
    }

    @Test("a cancelled job cannot be moved anywhere")
    static void cancelledIsFinal() {
        Job job = job();
        job.cancel();

        equal(JobState.CANCELLED, job.state(), "the state");
        throws_(IllegalStateException.class, () -> job.moveTo(JobState.SCHEDULED));
        throws_(IllegalStateException.class, () -> job.startAttempt(NOW));
    }

    @Test("a running job cannot be cancelled")
    static void runningCannotBeCancelled() {
        Job job = job();
        job.startAttempt(NOW);

        throws_(IllegalStateException.class, job::cancel);
    }

    @Test("a null state is rejected")
    static void nullStateIsRejected() {
        throws_(IllegalArgumentException.class, () -> job().moveTo(null));
    }

    // ---- rescheduling -----------------------------------------------------

    @Test("a settled job reschedules to its next fire")
    static void reschedulingAfterSuccess() {
        Job job = job();

        job.startAttempt(NOW);
        job.succeed(NOW.plusSeconds(1));
        job.rescheduleAt(NOW.plusDays(1));

        equal(JobState.SCHEDULED, job.state(), "the state");
        equal(NOW.plusDays(1), job.nextFireAt(), "the next fire time");
    }

    @Test("retryAt times a retry without leaving RETRYING")
    static void retryAtKeepsTheState() {
        // A retry and a reschedule mean different things to an operator: one
        // is the same fire being attempted again, the other is the next fire
        // of the schedule. Collapsing them makes `inspect` unable to tell a
        // retrying job from one that is simply due later.
        Job job = job();

        job.startAttempt(NOW);
        job.fail(NOW.plusSeconds(1), "timed out");
        job.retryAt(NOW.plusSeconds(3));

        equal(JobState.RETRYING, job.state(), "the state after timing the retry");
        equal(NOW.plusSeconds(3), job.nextFireAt(), "the retry time");
    }

    @Test("retryAt on a job that is not retrying is rejected")
    static void retryAtOutsideRetrying() {
        IllegalStateException error =
                throws_(IllegalStateException.class, () -> job().retryAt(NOW));

        contains(error.getMessage(), "not RETRYING", "the message says why");
    }

    @Test("rescheduling to no time is rejected")
    static void rescheduleRejectsNull() {
        Job job = job();
        job.startAttempt(NOW);
        job.succeed(NOW.plusSeconds(1));

        throws_(IllegalArgumentException.class, () -> job.rescheduleAt(null));
    }

    @Test("a job describes itself for a log line")
    static void describesItself() {
        Job job = job();

        contains(job.toString(), job.id().text(), "the id");
        contains(job.toString(), "nightly report", "the name");
        contains(job.toString(), "scheduled", "the state");
    }
}
