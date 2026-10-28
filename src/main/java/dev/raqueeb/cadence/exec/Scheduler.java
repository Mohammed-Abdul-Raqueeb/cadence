package dev.raqueeb.cadence.exec;

import dev.raqueeb.cadence.job.Job;
import dev.raqueeb.cadence.job.JobHandler;
import dev.raqueeb.cadence.job.JobId;
import dev.raqueeb.cadence.job.JobSpec;
import dev.raqueeb.cadence.job.JobState;
import dev.raqueeb.cadence.metrics.Metrics;
import dev.raqueeb.cadence.queue.ScheduleQueue;
import dev.raqueeb.cadence.store.Journal;
import dev.raqueeb.cadence.store.JournalEntry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Fires jobs when they are due.
 *
 * <p>One thread owns the queue and decides what runs; the worker pool runs
 * it. That split is the design:
 *
 * <ul>
 *   <li><b>One scheduling thread, so the queue needs no concurrent
 *       structure.</b> Every decision about what fires next is made in one
 *       place under one lock, which makes "fire this job, mark it running,
 *       write the record" a single atomic step. Spread across threads, those
 *       three interleave and a job fires twice.
 *   <li><b>Workers that never touch the queue.</b> A finished job is reported
 *       back through a completion that takes the lock, so a handler running
 *       for an hour blocks nothing.
 * </ul>
 *
 * <p>The loop sleeps until the next job is due rather than polling on a
 * tick. Polling at a fixed interval either wastes wakeups on an idle
 * scheduler or fires jobs late, and the interval is a number nobody can pick
 * well. Sleeping until the head of the queue is due has neither problem, and
 * {@link #submit} wakes the clock so a new job does not wait out a sleep
 * that was computed before it arrived.
 */
public final class Scheduler implements AutoCloseable {

    /** How long to wait when the queue is empty, so a wake is never missed forever. */
    private static final Duration IDLE_SLEEP = Duration.ofSeconds(30);

    private final Clock clock;
    private final ZoneId zone;
    private final WorkerPool workers;
    private final ScheduleQueue queue = new ScheduleQueue();
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final Map<JobId, Job> allJobs = new LinkedHashMap<>();
    private final Metrics metrics;
    private final Journal journal;
    private final Random jitterSource;
    private final Consumer<String> log;

    /** Guards the queue, the job table and the journal, in that order of use. */
    private final Object lock = new Object();

    private final AtomicBoolean running = new AtomicBoolean();
    private Thread loopThread;

    private Scheduler(Builder builder) {
        this.clock = builder.clock;
        this.zone = builder.zone;
        this.workers = builder.workers != null
                ? builder.workers
                : WorkerPool.sizedForMachine("cadence-worker");
        this.metrics = builder.metrics != null ? builder.metrics : new Metrics();
        this.journal = builder.journal;
        this.jitterSource = builder.jitterSource;
        this.log = builder.log;
    }

    /**
     * Starts building a scheduler.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Registers the code for a handler name.
     *
     * @param name    the name specs will refer to
     * @param handler the work to do
     */
    public void register(String name, JobHandler handler) {
        if (name == null || name.isBlank() || handler == null) {
            throw new IllegalArgumentException("a handler needs a name and an implementation");
        }
        synchronized (lock) {
            handlers.put(name.trim(), handler);
        }
    }

    /**
     * Removes a handler, leaving any job that uses it unable to run.
     *
     * <p>Here so a handler can be drained before a deployment removes its
     * code: jobs keep their schedules and are reported at each fire rather
     * than failing in a way that consumes their retries.
     *
     * <p>It is also what makes the "handler is gone" branch in
     * {@link #fireDueJobs} reachable. A guard rail no caller can trigger is
     * untestable, and an untestable guard rail is one nobody can show works.
     *
     * @param name the handler to remove
     * @return true when a handler of that name was registered
     */
    public boolean unregister(String name) {
        synchronized (lock) {
            return handlers.remove(name == null ? "" : name.trim()) != null;
        }
    }

    /**
     * Submits a job.
     *
     * @param spec what the job does and when
     * @return the new job's id
     * @throws IllegalArgumentException when the spec names an unregistered
     *     handler, or a recurring schedule that will never fire
     */
    public JobId submit(JobSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("cannot submit a null spec");
        }

        JobId id;
        synchronized (lock) {
            if (!handlers.containsKey(spec.handler())) {
                // Refused at submission rather than discovered at fire time.
                // A job that fails every fire because of a typo in a handler
                // name is a configuration error, and the moment to report one
                // is while the person who made it is still watching.
                throw new IllegalArgumentException("no handler named \"" + spec.handler()
                        + "\" is registered; known handlers are " + handlers.keySet());
            }

            LocalDateTime now = clock.now();
            LocalDateTime firstFire = firstFireTime(spec, now);

            id = JobId.generate();
            Job job = new Job(id, spec, now, firstFire);
            allJobs.put(id, job);
            queue.add(job);

            record(JournalEntry.Kind.SUBMITTED, job, Map.of(
                    "handler", spec.handler(),
                    "name", spec.name(),
                    "schedule", spec.schedule().map(Object::toString).orElse(""),
                    "payload", spec.payload(),
                    "fireAt", firstFire.toString()));
            metrics.counter("jobs.submitted").increment();
        }

        // Outside the lock: waking the clock only needs to happen, and a
        // scheduling thread that wakes and immediately blocks on this lock
        // would be doing nothing useful with the time.
        clock.wake();
        return id;
    }

    /** When a newly submitted job should first run. */
    private LocalDateTime firstFireTime(JobSpec spec, LocalDateTime now) {
        if (spec.schedule().isEmpty()) {
            // A one-shot job runs as soon as a worker is free.
            return now;
        }
        return spec.schedule().orElseThrow().nextAfter(now).orElseThrow(
                () -> new IllegalArgumentException("the schedule \""
                        + spec.schedule().orElseThrow().expression()
                        + "\" will never fire again, so the job would never run"));
    }

    /**
     * Cancels a job, if it has not already started.
     *
     * @param id the job to cancel
     * @return true when the job was cancelled
     */
    public boolean cancel(JobId id) {
        synchronized (lock) {
            Job job = allJobs.get(id);
            if (job == null || !job.state().isWaiting()) {
                return false;
            }

            queue.remove(id);
            job.cancel();
            record(JournalEntry.Kind.CANCELLED, job, Map.of());
            metrics.counter("jobs.cancelled").increment();
            return true;
        }
    }

    /**
     * Runs the scheduling loop on this thread until {@link #stop} is called.
     *
     * @throws IllegalStateException when the scheduler is already running
     */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("this scheduler is already running");
        }
        loopThread = Thread.currentThread();

        try {
            while (running.get()) {
                Duration wait = fireDueJobs();
                if (!running.get()) {
                    break;
                }
                try {
                    clock.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            running.set(false);
            loopThread = null;
        }
    }

    /**
     * Starts the scheduling loop on its own thread.
     *
     * @return the thread the loop is running on
     */
    public Thread start() {
        Thread thread = new Thread(this::run, "cadence-scheduler");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Fires every job that is due, and reports how long until the next one.
     *
     * <p>Public so a test can step the scheduler one decision at a time
     * rather than racing its loop. A test that starts the loop and waits is
     * testing the clock as much as the scheduler.
     *
     * @return how long to wait before there is anything else to do
     */
    public Duration fireDueJobs() {
        List<Fire> toRun = new ArrayList<>();
        Duration wait;

        synchronized (lock) {
            LocalDateTime now = clock.now();

            // Collected under the lock and run outside it. Calling a handler
            // here would hold the lock for the handler's whole duration, so
            // one slow job would stop the scheduler from firing any other.
            Optional<Job> due;
            while ((due = queue.pollDue(now)).isPresent()) {
                Job job = due.orElseThrow();

                JobHandler handler = handlers.get(job.spec().handler());
                if (handler == null) {
                    // The handler was registered at submission and is gone
                    // now, which means the registry changed under a live
                    // schedule. Reported and rescheduled rather than dropped.
                    log.accept("cadence: job " + job.id() + " wants handler \""
                            + job.spec().handler() + "\", which is no longer registered");
                    rescheduleOrRetire(job, now);
                    continue;
                }

                job.startAttempt(now);
                record(JournalEntry.Kind.STARTED, job,
                        Map.of("attempt", Long.valueOf(job.attempts())));
                metrics.counter("jobs.started").increment();

                toRun.add(new Fire(job, taskFor(job, handler)));
            }

            wait = waitUntilNextFire(clock.now());
        }

        for (Fire fire : toRun) {
            if (!workers.submit(fire.task())) {
                // The pool refused, so this fire cannot run. Completed as a
                // failed attempt so the retry policy decides what happens
                // next: the job is already in RUNNING with its attempt
                // counted, and leaving it there would strand it in a state
                // nothing else moves it out of.
                metrics.counter("jobs.rejected").increment();
                complete(fire.job(),
                        new RejectedExecutionException("the worker pool queue is full"), 0L);
            }
        }

        return wait;
    }

    /** A job and the task that will run it, paired so a rejection can be reported. */
    private record Fire(Job job, Runnable task) {
    }

    /** Thrown into {@link #complete} when the pool cannot take a fire. */
    private static final class RejectedExecutionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RejectedExecutionException(String message) {
            super(message);
        }
    }

    /** The work a worker does for one fire. */
    private Runnable taskFor(Job job, JobHandler handler) {
        JobHandler.JobContext context = new JobHandler.JobContext(
                job.id(), job.spec().name(), job.spec().payload(), job.attempts());

        return () -> {
            long startNanos = System.nanoTime();
            Throwable failure = null;
            try {
                handler.run(context);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = e;
            } catch (Exception | Error e) {
                // Error is caught too. An OutOfMemoryError in one handler
                // should not take the scheduler's bookkeeping with it, and a
                // job left in RUNNING for ever is unrecoverable without a
                // restart.
                failure = e;
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            complete(job, failure, elapsedNanos);
        };
    }

    /** Records an attempt's outcome and decides what happens next. */
    private void complete(Job job, Throwable failure, long elapsedNanos) {
        try {
            completeUnderLock(job, failure, elapsedNanos);
        } finally {
            // The queue almost certainly changed -- a retry was added, or a
            // recurring job was requeued -- and this runs on a worker thread
            // while the scheduling thread is asleep on a wait computed before
            // any of that. Without this wake a retry due in 200ms waits out
            // the whole idle ceiling instead, which is how a demo first
            // showed a retry never happening at all.
            clock.wake();
        }
    }

    /** The body of {@link #complete}, holding the lock. */
    private void completeUnderLock(Job job, Throwable failure, long elapsedNanos) {
        synchronized (lock) {
            LocalDateTime now = clock.now();
            metrics.histogram("jobs.duration.ms").record(elapsedNanos / 1_000_000);

            if (failure == null) {
                job.succeed(now);
                record(JournalEntry.Kind.SUCCEEDED, job,
                        Map.of("durationMs", Long.valueOf(elapsedNanos / 1_000_000)));
                metrics.counter("jobs.succeeded").increment();
                rescheduleOrRetire(job, now);
                return;
            }

            String message = describe(failure);
            boolean willRetry = job.fail(now, message);
            record(JournalEntry.Kind.FAILED, job, Map.of(
                    "error", message,
                    "willRetry", Boolean.valueOf(willRetry),
                    "attempt", Long.valueOf(Math.max(1, job.attempts()))));
            metrics.counter(willRetry ? "jobs.retried" : "jobs.failed").increment();

            if (willRetry) {
                Duration delay =
                        job.spec().retryPolicy().delayBefore(job.attempts() + 1, jitterSource);
                job.retryAt(now.plus(delay));
                queue.add(job);
            } else {
                rescheduleOrRetire(job, now);
            }
        }
    }

    /** Puts a recurring job back in the queue, or leaves a one-shot job settled. */
    private void rescheduleOrRetire(Job job, LocalDateTime now) {
        Optional<LocalDateTime> nextFire = job.spec().schedule()
                .flatMap(schedule -> schedule.nextAfter(now));

        if (nextFire.isEmpty()) {
            // Either a one-shot job, or a schedule with no future fire. Both
            // end the same way: the job stays in the table for inspection but
            // leaves the queue.
            return;
        }

        job.rescheduleAt(nextFire.orElseThrow());
        queue.add(job);
        record(JournalEntry.Kind.RESCHEDULED, job,
                Map.of("fireAt", nextFire.orElseThrow().toString()));
    }

    /** How long until the next job is due, bounded so a wake is never lost. */
    private Duration waitUntilNextFire(LocalDateTime now) {
        Optional<Job> next = queue.peek();
        if (next.isEmpty()) {
            return IDLE_SLEEP;
        }

        Duration until = Duration.between(now, next.orElseThrow().nextFireAt());
        if (until.isNegative() || until.isZero()) {
            return Duration.ZERO;
        }
        return until.compareTo(IDLE_SLEEP) > 0 ? IDLE_SLEEP : until;
    }

    /** A failure's message, falling back to its type when it has none. */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            // A NullPointerException often has no message, and "failed: null"
            // tells an operator nothing at all.
            return failure.getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName() + ": " + message;
    }

    /** Writes a journal record, if there is a journal. Caller holds the lock. */
    private void record(JournalEntry.Kind kind, Job job, Map<String, Object> data) {
        if (journal == null) {
            return;
        }
        long millis = clock.now().atZone(zone).toInstant().toEpochMilli();
        journal.append(new JournalEntry(kind, job.id(), millis, data));
    }

    /** Stops the scheduling loop. */
    public void stop() {
        running.set(false);
        clock.wake();
        Thread thread = loopThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * A job by id.
     *
     * @param id the job to look for
     * @return the job, or empty when no such job was submitted
     */
    public Optional<Job> job(JobId id) {
        synchronized (lock) {
            return Optional.ofNullable(allJobs.get(id));
        }
    }

    /**
     * Every job, in submission order.
     *
     * @return a snapshot list
     */
    public List<Job> jobs() {
        synchronized (lock) {
            return List.copyOf(allJobs.values());
        }
    }

    /**
     * The jobs in a given state.
     *
     * @param state the state to filter by
     * @return a snapshot list
     */
    public List<Job> jobsIn(JobState state) {
        synchronized (lock) {
            return allJobs.values().stream().filter(job -> job.state() == state).toList();
        }
    }

    /**
     * How many jobs are queued.
     *
     * @return the queue size
     */
    public int queuedCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /**
     * The registered handler names.
     *
     * @return a snapshot list
     */
    public List<String> handlerNames() {
        synchronized (lock) {
            return handlers.keySet().stream().sorted().toList();
        }
    }

    /**
     * The metrics this scheduler records into.
     *
     * @return the metrics registry
     */
    public Metrics metrics() {
        return metrics;
    }

    /**
     * The worker pool.
     *
     * @return the pool running the jobs
     */
    public WorkerPool workers() {
        return workers;
    }

    /**
     * Whether the loop is running.
     *
     * @return true between {@link #run} starting and {@link #stop}
     */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void close() {
        stop();
        workers.shutdown(Duration.ofSeconds(10));
        if (journal != null) {
            journal.close();
        }
    }

    /** Builds a {@link Scheduler}. */
    public static final class Builder {

        private Clock clock = Clock.system();
        private ZoneId zone = ZoneId.systemDefault();
        private WorkerPool workers;
        private Metrics metrics;
        private Journal journal;
        private Random jitterSource = new Random();
        private Consumer<String> log = System.err::println;

        private Builder() {
        }

        /**
         * Sets the clock.
         *
         * @param clock where to read the time from
         * @return this builder
         */
        public Builder clock(Clock clock) {
            if (clock == null) {
                throw new IllegalArgumentException("a scheduler needs a clock");
            }
            this.clock = clock;
            return this;
        }

        /**
         * Sets the zone used to stamp journal records.
         *
         * @param zone the zone
         * @return this builder
         */
        public Builder zone(ZoneId zone) {
            if (zone == null) {
                throw new IllegalArgumentException("a scheduler needs a zone");
            }
            this.zone = zone;
            return this;
        }

        /**
         * Sets the worker pool.
         *
         * @param workers the pool to run jobs on
         * @return this builder
         */
        public Builder workers(WorkerPool workers) {
            this.workers = workers;
            return this;
        }

        /**
         * Sets the metrics registry.
         *
         * @param metrics where to record counters and timings
         * @return this builder
         */
        public Builder metrics(Metrics metrics) {
            this.metrics = metrics;
            return this;
        }

        /**
         * Sets the journal.
         *
         * @param journal where to record events, or null for none
         * @return this builder
         */
        public Builder journal(Journal journal) {
            this.journal = journal;
            return this;
        }

        /**
         * Sets the source of retry jitter.
         *
         * @param random a seeded Random makes retries reproducible in a test
         * @return this builder
         */
        public Builder jitterSource(Random random) {
            if (random == null) {
                throw new IllegalArgumentException("a scheduler needs a jitter source");
            }
            this.jitterSource = random;
            return this;
        }

        /**
         * Sets where warnings go.
         *
         * @param log a sink for one-line messages
         * @return this builder
         */
        public Builder log(Consumer<String> log) {
            if (log == null) {
                throw new IllegalArgumentException("a scheduler needs somewhere to log");
            }
            this.log = log;
            return this;
        }

        /**
         * Builds the scheduler.
         *
         * @return the scheduler, not yet running
         */
        public Scheduler build() {
            return new Scheduler(this);
        }
    }
}
