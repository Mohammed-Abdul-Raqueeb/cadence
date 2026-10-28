package dev.raqueeb.cadence.job;

import dev.raqueeb.cadence.cron.CronExpression;
import java.time.Duration;
import java.util.Optional;

/**
 * What a job is: a handler to call, when to call it, and what to pass it.
 *
 * <p>A spec is the part of a job that survives a restart, so everything here
 * is data. In particular the handler is named rather than held: a
 * {@code Runnable} cannot be written to a journal, and a scheduler that
 * stored one would lose every job the moment the process died. The name is
 * resolved against a registry at run time, which also means an operator can
 * see from the journal what a job was going to do.
 *
 * <p>Instances are immutable.
 */
public final class JobSpec {

    /** The handler name, resolved against the scheduler's registry. */
    private final String handler;

    /** A human label for logs and {@code cadence list}. */
    private final String name;

    /** The schedule, or null for a job that runs once. */
    private final CronExpression schedule;

    /** Opaque payload handed to the handler. */
    private final String payload;

    /** What to do when the handler throws. */
    private final RetryPolicy retryPolicy;

    /** How long a single attempt may run before it is abandoned. */
    private final Duration timeout;

    private JobSpec(Builder builder) {
        this.handler = builder.handler;
        this.name = builder.name;
        this.schedule = builder.schedule;
        this.payload = builder.payload;
        this.retryPolicy = builder.retryPolicy;
        this.timeout = builder.timeout;
    }

    /**
     * Starts building a spec for the named handler.
     *
     * @param handler the handler name, as registered with the scheduler
     * @return a builder
     */
    public static Builder forHandler(String handler) {
        return new Builder(handler);
    }

    /**
     * The handler name.
     *
     * @return the name to resolve against the registry
     */
    public String handler() {
        return handler;
    }

    /**
     * The job's label.
     *
     * @return the display name, defaulting to the handler name
     */
    public String name() {
        return name;
    }

    /**
     * The schedule, when the job recurs.
     *
     * @return the cron expression, or empty for a one-shot job
     */
    public Optional<CronExpression> schedule() {
        return Optional.ofNullable(schedule);
    }

    /**
     * Whether the job runs more than once.
     *
     * @return true when a schedule is set
     */
    public boolean isRecurring() {
        return schedule != null;
    }

    /**
     * The payload handed to the handler.
     *
     * @return the payload, empty string when none was set
     */
    public String payload() {
        return payload;
    }

    /**
     * The retry policy.
     *
     * @return what to do when the handler throws
     */
    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /**
     * The per-attempt timeout.
     *
     * @return how long one attempt may run
     */
    public Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return name + " (" + handler + ", " + (schedule == null ? "one-shot" : schedule) + ")";
    }

    /** Builds a {@link JobSpec}, validating as it goes. */
    public static final class Builder {

        private final String handler;
        private String name;
        private CronExpression schedule;
        private String payload = "";
        private RetryPolicy retryPolicy = RetryPolicy.standard();
        private Duration timeout = Duration.ofMinutes(5);

        private Builder(String handler) {
            if (handler == null || handler.isBlank()) {
                throw new IllegalArgumentException("a job needs a handler name");
            }
            this.handler = handler.trim();
            this.name = this.handler;
        }

        /**
         * Sets the display name.
         *
         * @param name a non-blank label
         * @return this builder
         */
        public Builder name(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a job name cannot be blank");
            }
            this.name = name.trim();
            return this;
        }

        /**
         * Sets the schedule from a cron expression.
         *
         * @param cron the expression or macro
         * @return this builder
         */
        public Builder schedule(String cron) {
            return schedule(CronExpression.parse(cron));
        }

        /**
         * Sets the schedule.
         *
         * @param cron the parsed expression
         * @return this builder
         */
        public Builder schedule(CronExpression cron) {
            this.schedule = cron;
            return this;
        }

        /**
         * Sets the payload.
         *
         * @param payload an opaque string, often JSON
         * @return this builder
         */
        public Builder payload(String payload) {
            this.payload = payload == null ? "" : payload;
            return this;
        }

        /**
         * Sets the retry policy.
         *
         * @param policy what to do when the handler throws
         * @return this builder
         */
        public Builder retryPolicy(RetryPolicy policy) {
            if (policy == null) {
                throw new IllegalArgumentException(
                        "a retry policy cannot be null; use RetryPolicy.none()");
            }
            this.retryPolicy = policy;
            return this;
        }

        /**
         * Sets the per-attempt timeout.
         *
         * @param timeout a positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException(
                        "a timeout must be positive, got " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return the immutable spec
         */
        public JobSpec build() {
            return new JobSpec(this);
        }
    }
}
