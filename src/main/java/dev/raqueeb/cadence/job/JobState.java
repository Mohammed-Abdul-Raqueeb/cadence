package dev.raqueeb.cadence.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a job is in its life.
 *
 * <p>The legal transitions are declared here rather than enforced by whoever
 * happens to be mutating a job. A scheduler has several paths that change
 * state — the fire loop, a worker finishing, a retry timer, an operator
 * cancelling — and each one is a chance to move a job somewhere it should not
 * go. Keeping the graph in one place means a new path cannot invent a
 * transition without touching this file.
 *
 * <pre>
 *   SCHEDULED ──fire──▶ RUNNING ──ok───▶ SUCCEEDED
 *       ▲                  │
 *       │                  ├──fail, attempts left──▶ RETRYING ──┐
 *       │                  │                                     │
 *       │                  └──fail, no attempts left──▶ FAILED    │
 *       └─────────────────── reschedule ◀──────────────────────┘
 *
 *   SCHEDULED, RETRYING ──cancel──▶ CANCELLED
 * </pre>
 */
public enum JobState {

    /** Waiting in the queue for its fire time. */
    SCHEDULED,

    /** Handed to a worker and executing now. */
    RUNNING,

    /** Finished without throwing. A recurring job returns to SCHEDULED. */
    SUCCEEDED,

    /** Failed with attempts remaining, waiting out its backoff. */
    RETRYING,

    /** Failed and out of attempts. Terminal for this fire. */
    FAILED,

    /** Cancelled before it ran. Terminal. */
    CANCELLED;

    /**
     * The states this one may move to.
     *
     * @return the legal next states, possibly empty
     */
    public Set<JobState> allowedNext() {
        return switch (this) {
            case SCHEDULED -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, RETRYING, FAILED);
            // A recurring job goes back to the queue after either outcome; a
            // one-shot job stops here, which is the job's business rather
            // than the state machine's.
            case SUCCEEDED, FAILED -> EnumSet.of(SCHEDULED);
            case RETRYING -> EnumSet.of(RUNNING, CANCELLED, SCHEDULED);
            case CANCELLED -> EnumSet.noneOf(JobState.class);
        };
    }

    /**
     * Whether moving to {@code next} is legal.
     *
     * @param next the state being moved to
     * @return true when the transition is allowed
     */
    public boolean canMoveTo(JobState next) {
        return allowedNext().contains(next);
    }

    /**
     * Whether no further transition is possible.
     *
     * @return true for states with no outgoing edges
     */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /**
     * Whether the job is finished with this fire, successfully or not.
     *
     * <p>Distinct from {@link #isTerminal()}: a failed recurring job is
     * finished with this fire but will be scheduled again.
     *
     * @return true when the current attempt has concluded
     */
    public boolean isSettled() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /**
     * Whether the job is waiting to run rather than running or finished.
     *
     * @return true for SCHEDULED and RETRYING
     */
    public boolean isWaiting() {
        return this == SCHEDULED || this == RETRYING;
    }

    /**
     * The lower-case name used in JSON and CLI output.
     *
     * @return the state name in lower case
     */
    public String wireName() {
        return name().toLowerCase();
    }

    /**
     * Parses a state from its wire name.
     *
     * @param wireName the name as written in JSON or on the command line
     * @return the state
     * @throws IllegalArgumentException when the name is not a state
     */
    public static JobState fromWireName(String wireName) {
        if (wireName != null) {
            for (JobState state : values()) {
                if (state.wireName().equals(wireName.toLowerCase())) {
                    return state;
                }
            }
        }
        throw new IllegalArgumentException("not a job state: " + wireName
                + " (expected one of " + String.join(", ", wireNames()) + ")");
    }

    /**
     * Every state's wire name, for error messages and help text.
     *
     * @return the names in declaration order
     */
    public static java.util.List<String> wireNames() {
        return java.util.Arrays.stream(values()).map(JobState::wireName).toList();
    }
}
