package dev.raqueeb.cadence.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fixed set of threads that run submitted tasks.
 *
 * <p>Written on {@link Thread} and a {@link BlockingQueue} rather than taken
 * from {@code java.util.concurrent.Executors}, for two behaviours the stock
 * pools do not offer together:
 *
 * <ul>
 *   <li><b>A bounded queue that rejects rather than grows.</b>
 *       {@code newFixedThreadPool} uses an unbounded queue, so a scheduler
 *       whose jobs run slower than they fire accumulates tasks until the heap
 *       is gone. An out-of-memory error two hours into a backlog is a far
 *       worse failure than a rejected submission at the moment the backlog
 *       starts, which the scheduler can see, count and report.
 *   <li><b>A shutdown that drains.</b> {@code shutdownNow} abandons queued
 *       tasks and {@code shutdown} waits for them with no timeout.
 *       {@link #shutdown} here finishes what is queued, up to a deadline, and
 *       then interrupts — which is what an operator restarting a scheduler
 *       actually wants.
 * </ul>
 *
 * <p>Thread-safe.
 */
public final class WorkerPool implements AutoCloseable {

    private final List<Thread> workers;
    private final BlockingQueue<Runnable> queue;
    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /** Set once, so workers stop taking new work but still drain the queue. */
    private volatile boolean stopping;

    /**
     * Starts a pool.
     *
     * @param size      how many threads
     * @param queueSize how many tasks may wait; beyond this, submission is
     *     refused
     * @param namePrefix what to name the threads, for a thread dump
     */
    public WorkerPool(int size, int queueSize, String namePrefix) {
        if (size < 1) {
            throw new IllegalArgumentException("a pool needs at least one thread, got " + size);
        }
        if (queueSize < 1) {
            throw new IllegalArgumentException(
                    "a pool needs room for at least one waiting task, got " + queueSize);
        }

        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.workers = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            // Named, because the first thing anyone does with a stuck
            // scheduler is take a thread dump, and "Thread-7" tells them
            // nothing about which pool it belongs to.
            Thread worker = new Thread(this::workerLoop, namePrefix + "-" + i);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    /**
     * A pool sized to the machine, with a queue four deep per thread.
     *
     * @param namePrefix what to name the threads
     * @return a started pool
     */
    public static WorkerPool sizedForMachine(String namePrefix) {
        int size = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new WorkerPool(size, size * 4, namePrefix);
    }

    /** Takes tasks until stopping and the queue is empty. */
    private void workerLoop() {
        while (true) {
            Runnable task;
            try {
                // A poll with a timeout rather than take(), so a worker
                // notices `stopping` even when no task ever arrives. take()
                // would block forever on an idle pool and only unblock on
                // interruption, which is a blunter instrument than it needs.
                task = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // Interrupted during shutdown's grace period: stop waiting,
                // but drain anything already queued before leaving.
                Thread.currentThread().interrupt();
                return;
            }

            if (task == null) {
                if (stopping && queue.isEmpty()) {
                    return;
                }
                continue;
            }

            busy.incrementAndGet();
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                // A task that throws must not kill its worker: the pool would
                // shrink silently and the scheduler would slow down with no
                // indication why. Handler failures are reported by the
                // scheduler, which wraps the handler; anything reaching here
                // is a bug in that wrapper.
                System.err.println("cadence: a worker task threw, which should not happen: " + e);
            } finally {
                busy.decrementAndGet();
                completed.incrementAndGet();
            }
        }
    }

    /**
     * Queues a task.
     *
     * @param task the work to run
     * @return true when the task was queued, false when the queue was full or
     *     the pool is stopping
     */
    public boolean submit(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("cannot submit a null task");
        }
        if (stopping) {
            rejected.incrementAndGet();
            return false;
        }

        boolean queued = queue.offer(task);
        if (!queued) {
            rejected.incrementAndGet();
        }
        return queued;
    }

    /**
     * Stops taking work, lets what is queued finish, then interrupts.
     *
     * @param grace how long to let queued work finish
     * @return true when everything finished within the grace period
     */
    public boolean shutdown(Duration grace) {
        stopping = true;

        long deadline = System.nanoTime() + Math.max(0, grace.toNanos());
        for (Thread worker : workers) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                worker.join(remaining / 1_000_000, (int) (remaining % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        boolean clean = workers.stream().noneMatch(Thread::isAlive);
        if (!clean) {
            // The grace period expired with work still running. Interrupting
            // is the only lever left; a handler that ignores interruption
            // will outlive this call, which is documented on JobHandler.
            workers.forEach(Thread::interrupt);
        }
        return clean;
    }

    /**
     * How many tasks are running right now.
     *
     * @return the busy thread count
     */
    public int busyCount() {
        return busy.get();
    }

    /**
     * How many tasks are waiting.
     *
     * @return the queue depth
     */
    public int queueDepth() {
        return queue.size();
    }

    /**
     * How many tasks have finished.
     *
     * @return the completed count
     */
    public long completedCount() {
        return completed.get();
    }

    /**
     * How many submissions were refused.
     *
     * <p>Worth watching: a non-zero value means jobs fired that could not be
     * run, which is the signal that the pool is undersized for the schedule.
     *
     * @return the rejected count
     */
    public long rejectedCount() {
        return rejected.get();
    }

    /**
     * How many threads the pool has.
     *
     * @return the pool size
     */
    public int size() {
        return workers.size();
    }

    /**
     * Whether the pool has been asked to stop.
     *
     * @return true after {@link #shutdown} has been called
     */
    public boolean isStopping() {
        return stopping;
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(5));
    }
}
