package dev.raqueeb.cadence.job;

/**
 * The work a job does.
 *
 * <p>Registered under a name, which is what a {@link JobSpec} stores. The
 * indirection is what lets a job survive a restart: a lambda cannot be
 * written to a journal, so the journal holds the name and the registry holds
 * the code.
 *
 * <p>A handler is called from a worker thread and may be called concurrently
 * for different jobs sharing the same handler, so an implementation that
 * keeps state has to guard it.
 */
@FunctionalInterface
public interface JobHandler {

    /**
     * Does the work.
     *
     * <p>Throwing is how a handler reports failure; the scheduler turns that
     * into a retry or a give-up according to the job's policy. Returning
     * normally means success.
     *
     * <p>A handler that may run for a while should check
     * {@link Thread#isInterrupted()} or let an {@link InterruptedException}
     * out. The scheduler interrupts a worker whose job has passed its
     * timeout, and a handler that ignores the interrupt holds the worker
     * until it finishes regardless.
     *
     * @param context the job's identity and payload
     * @throws Exception to report failure
     */
    void run(JobContext context) throws Exception;

    /**
     * What a handler is told about the job it is running.
     *
     * @param jobId    the job's identity, for log correlation
     * @param jobName  the job's label
     * @param payload  the payload from the spec
     * @param attempt  which attempt this is, counting from 1
     */
    record JobContext(JobId jobId, String jobName, String payload, int attempt) {

        /**
         * Whether this is a retry rather than the first attempt.
         *
         * <p>Handlers that are not idempotent need to know, and the ones that
         * are often still want to log it.
         *
         * @return true when at least one attempt has already failed
         */
        public boolean isRetry() {
            return attempt > 1;
        }
    }
}
