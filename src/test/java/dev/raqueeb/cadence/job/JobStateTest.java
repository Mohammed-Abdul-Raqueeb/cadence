package dev.raqueeb.cadence.job;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;

/** The job state machine's declared transitions. */
final class JobStateTest {

    @Test("a scheduled job may run or be cancelled")
    static void fromScheduled() {
        isTrue(JobState.SCHEDULED.canMoveTo(JobState.RUNNING), "scheduled to run");
        isTrue(JobState.SCHEDULED.canMoveTo(JobState.CANCELLED), "scheduled to cancel");
        isFalse(JobState.SCHEDULED.canMoveTo(JobState.SUCCEEDED), "scheduled to succeed directly");
        isFalse(JobState.SCHEDULED.canMoveTo(JobState.FAILED), "scheduled to fail directly");
    }

    @Test("a running job may succeed, retry or fail")
    static void fromRunning() {
        isTrue(JobState.RUNNING.canMoveTo(JobState.SUCCEEDED), "running to succeed");
        isTrue(JobState.RUNNING.canMoveTo(JobState.RETRYING), "running to retry");
        isTrue(JobState.RUNNING.canMoveTo(JobState.FAILED), "running to fail");
    }

    @Test("a running job cannot be cancelled")
    static void runningCannotBeCancelled() {
        // Cancellation stops a job from starting; it does not reach into a
        // worker and stop one that already has. A job that is running has to
        // reach an outcome, and pretending otherwise would leave the worker
        // writing a result for a job the scheduler thinks is finished.
        isFalse(JobState.RUNNING.canMoveTo(JobState.CANCELLED), "running to cancel");
    }

    @Test("a retrying job may run again, be cancelled, or go back to the queue")
    static void fromRetrying() {
        isTrue(JobState.RETRYING.canMoveTo(JobState.RUNNING), "retrying to run");
        isTrue(JobState.RETRYING.canMoveTo(JobState.CANCELLED), "retrying to cancel");
        isTrue(JobState.RETRYING.canMoveTo(JobState.SCHEDULED), "retrying to reschedule");
    }

    @Test("a settled job returns to the queue, for a recurring schedule")
    static void settledReschedules() {
        isTrue(JobState.SUCCEEDED.canMoveTo(JobState.SCHEDULED), "succeeded to reschedule");
        isTrue(JobState.FAILED.canMoveTo(JobState.SCHEDULED), "failed to reschedule");
    }

    @Test("a succeeded job cannot go straight back to running")
    static void succeededDoesNotRunDirectly() {
        // It has to pass through SCHEDULED, which is where the next fire time
        // is computed. Skipping that is how a job runs twice for one fire.
        isFalse(JobState.SUCCEEDED.canMoveTo(JobState.RUNNING), "succeeded to run again directly");
    }

    @Test("cancelled is the only terminal state")
    static void onlyCancelledIsTerminal() {
        isTrue(JobState.CANCELLED.isTerminal(), "cancelled to be terminal");

        for (JobState state : JobState.values()) {
            if (state != JobState.CANCELLED) {
                isFalse(state.isTerminal(), state + " to be terminal");
            }
        }
    }

    @Test("settled is not the same as terminal")
    static void settledIsNotTerminal() {
        // A failed recurring job is finished with this fire and will still be
        // scheduled again. Conflating the two retires jobs that should repeat.
        isTrue(JobState.FAILED.isSettled(), "failed to be settled");
        isFalse(JobState.FAILED.isTerminal(), "failed to be terminal");

        isTrue(JobState.SUCCEEDED.isSettled(), "succeeded to be settled");
        isFalse(JobState.SUCCEEDED.isTerminal(), "succeeded to be terminal");
    }

    @Test("retrying is waiting, not settled")
    static void retryingIsWaiting() {
        isTrue(JobState.RETRYING.isWaiting(), "retrying to be waiting");
        isFalse(JobState.RETRYING.isSettled(), "retrying to be settled");

        isTrue(JobState.SCHEDULED.isWaiting(), "scheduled to be waiting");
        isFalse(JobState.RUNNING.isWaiting(), "running to be waiting");
    }

    @Test("no state may move to itself")
    static void noSelfTransitions() {
        // A self-transition would be a state change that is not a change,
        // which the journal would record as an event with nothing in it.
        for (JobState state : JobState.values()) {
            isFalse(state.canMoveTo(state), state + " to move to itself");
        }
    }

    @Test("every state is reachable from scheduled")
    static void everyStateIsReachable() {
        // A state nothing can reach is dead code in the machine, and a typo
        // in the transition table is exactly how one appears.
        java.util.Set<JobState> reached = java.util.EnumSet.of(JobState.SCHEDULED);
        java.util.Deque<JobState> queue = new java.util.ArrayDeque<>(reached);

        while (!queue.isEmpty()) {
            for (JobState next : queue.poll().allowedNext()) {
                if (reached.add(next)) {
                    queue.add(next);
                }
            }
        }

        equal(JobState.values().length, reached.size(),
                "the number of states reachable from SCHEDULED: " + reached);
    }

    @Test("wire names are lower case and round-trip")
    static void wireNamesRoundTrip() {
        for (JobState state : JobState.values()) {
            equal(state.name().toLowerCase(), state.wireName(), "the wire name of " + state);
            equal(state, JobState.fromWireName(state.wireName()), "the state read back");
        }
    }

    @Test("a wire name is read case-insensitively")
    static void wireNamesAreCaseInsensitive() {
        equal(JobState.RUNNING, JobState.fromWireName("RUNNING"), "an upper-case name");
        equal(JobState.RUNNING, JobState.fromWireName("Running"), "a mixed-case name");
    }

    @Test("an unknown wire name lists the real ones")
    static void unknownWireNameIsRejected() {
        IllegalArgumentException error = throws_(IllegalArgumentException.class,
                () -> JobState.fromWireName("sleeping"));

        contains(error.getMessage(), "sleeping", "the message quotes what was given");
        contains(error.getMessage(), "scheduled", "the message lists a real state");

        throws_(IllegalArgumentException.class, () -> JobState.fromWireName(null));
    }
}
