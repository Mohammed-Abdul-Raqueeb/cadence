package dev.raqueeb.cadence.job;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * A job's identity, specification and current state.
 *
 * <p>Mutable, and deliberately not thread-safe. Every mutation here goes
 * through the scheduler's own lock, so a second layer of synchronisation
 * inside the job would cost something and guarantee nothing — two
 * independently-atomic mutations still interleave into an inconsistent pair.
 * The class is kept honest about that rather than wearing a {@code
 * synchronized} that implies more than it delivers.
 *
 * <p>State changes go through {@link #moveTo}, which refuses a transition
 * {@link JobState} does not allow.
 */
public final class Job {

    private final JobId id;
    private final JobSpec spec;
    private final LocalDateTime createdAt;

    private JobState state;
    private LocalDateTime nextFireAt;

    /** Attempts made against the current fire, reset when it settles. */
    private int attempts;

    /** Fires that have completed, successfully or not. */
    private long runs;

    /** Fires that ended in FAILED. */
    private long failures;

    private LocalDateTime lastStartedAt;
    private LocalDateTime lastFinishedAt;
    private Duration lastDuration;
    private String lastError;

    /**
     * Creates a job in the SCHEDULED state.
     *
     * @param id         the job's identity
     * @param spec       what the job does
     * @param createdAt  when it was submitted
     * @param nextFireAt when it should first run
     */
    public Job(JobId id, JobSpec spec, LocalDateTime createdAt, LocalDateTime nextFireAt) {
        if (id == null || spec == null || createdAt == null || nextFireAt == null) {
            throw new IllegalArgumentException("a job needs an id, a spec, a creation time"
                    + " and a first fire time");
        }
        this.id = id;
        this.spec = spec;
        this.createdAt = createdAt;
        this.nextFireAt = nextFireAt;
        this.state = JobState.SCHEDULED;
    }

    /**
     * Moves the job to a new state.
     *
     * @param next the state to move to
     * @throws IllegalStateException when {@link JobState} does not allow it
     */
    public void moveTo(JobState next) {
        if (next == null) {
            throw new IllegalArgumentException("a job cannot move to a null state");
        }
        if (!state.canMoveTo(next)) {
            // Thrown rather than logged. A scheduler with several paths that
            // change state will eventually have one that is wrong, and a
            // silently-ignored illegal transition leaves a job stuck in a
            // state nothing will move it out of -- which looks like a hang.
            throw new IllegalStateException("job " + id + " cannot move from " + state
                    + " to " + next + "; " + state + " allows " + state.allowedNext());
        }
        this.state = next;
    }

    /**
     * Records that an attempt has started.
     *
     * @param when the start time
     */
    public void startAttempt(LocalDateTime when) {
        moveTo(JobState.RUNNING);
        this.attempts++;
        this.lastStartedAt = when;
    }

    /**
     * Records a successful attempt.
     *
     * @param when the finish time
     */
    public void succeed(LocalDateTime when) {
        moveTo(JobState.SUCCEEDED);
        finish(when);
        this.runs++;
        this.attempts = 0;
        this.lastError = null;
    }

    /**
     * Records a failed attempt, choosing between a retry and giving up.
     *
     * @param when  the finish time
     * @param error the failure, as a message
     * @return true when the job will be retried
     */
    public boolean fail(LocalDateTime when, String error) {
        boolean retrying = spec.retryPolicy().shouldRetry(attempts);
        moveTo(retrying ? JobState.RETRYING : JobState.FAILED);
        finish(when);
        this.lastError = error;

        if (!retrying) {
            this.runs++;
            this.failures++;
            this.attempts = 0;
        }
        return retrying;
    }

    /** Shared bookkeeping for both outcomes. */
    private void finish(LocalDateTime when) {
        this.lastFinishedAt = when;
        this.lastDuration = lastStartedAt == null ? Duration.ZERO
                : Duration.between(lastStartedAt, when);
    }

    /**
     * Schedules the job's next fire.
     *
     * @param when when it should run next
     */
    public void rescheduleAt(LocalDateTime when) {
        if (when == null) {
            throw new IllegalArgumentException("a job cannot be rescheduled to no time");
        }
        moveTo(JobState.SCHEDULED);
        this.nextFireAt = when;
    }

    /**
     * Sets the time a retry should run, without leaving the RETRYING state.
     *
     * <p>Separate from {@link #rescheduleAt} because the two mean different
     * things to an operator: a retry is the same fire being attempted again,
     * while a reschedule is the next fire of the schedule. Collapsing them
     * would make {@code cadence inspect} unable to tell a job that is
     * retrying from one that is simply due later.
     *
     * @param when when the retry should run
     */
    public void retryAt(LocalDateTime when) {
        if (state != JobState.RETRYING) {
            throw new IllegalStateException(
                    "job " + id + " is " + state + ", not RETRYING, so it has no retry to time");
        }
        this.nextFireAt = when;
    }

    /** Cancels the job. */
    public void cancel() {
        moveTo(JobState.CANCELLED);
    }

    /**
     * The job's identity.
     *
     * @return the id
     */
    public JobId id() {
        return id;
    }

    /**
     * What the job does.
     *
     * @return the spec
     */
    public JobSpec spec() {
        return spec;
    }

    /**
     * The current state.
     *
     * @return the state
     */
    public JobState state() {
        return state;
    }

    /**
     * When the job next runs.
     *
     * @return the next fire time
     */
    public LocalDateTime nextFireAt() {
        return nextFireAt;
    }

    /**
     * When the job was submitted.
     *
     * @return the creation time
     */
    public LocalDateTime createdAt() {
        return createdAt;
    }

    /**
     * Attempts made against the current fire.
     *
     * @return the attempt count, reset when a fire settles
     */
    public int attempts() {
        return attempts;
    }

    /**
     * Fires that have completed.
     *
     * @return the run count
     */
    public long runs() {
        return runs;
    }

    /**
     * Fires that ended in failure.
     *
     * @return the failure count
     */
    public long failures() {
        return failures;
    }

    /**
     * How long the last attempt took.
     *
     * @return the duration, or empty when the job has not run
     */
    public Optional<Duration> lastDuration() {
        return Optional.ofNullable(lastDuration);
    }

    /**
     * When the last attempt finished.
     *
     * @return the finish time, or empty when the job has not run
     */
    public Optional<LocalDateTime> lastFinishedAt() {
        return Optional.ofNullable(lastFinishedAt);
    }

    /**
     * The last failure's message.
     *
     * @return the message, or empty when the last attempt succeeded
     */
    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    @Override
    public String toString() {
        return id + " " + spec.name() + " [" + state.wireName() + "] next " + nextFireAt;
    }
}
