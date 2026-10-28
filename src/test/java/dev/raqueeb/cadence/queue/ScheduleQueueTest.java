package dev.raqueeb.cadence.queue;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import dev.raqueeb.cadence.job.Job;
import dev.raqueeb.cadence.job.JobId;
import dev.raqueeb.cadence.job.JobSpec;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** The fire-time heap. */
final class ScheduleQueueTest {

    private static final LocalDateTime BASE = LocalDateTime.parse("2026-10-28T00:00");

    /** A job firing {@code minutes} after the base time. */
    private static Job at(int minutes) {
        return at(minutes, JobId.generate());
    }

    private static Job at(int minutes, JobId id) {
        return new Job(id, JobSpec.forHandler("h").build(), BASE, BASE.plusMinutes(minutes));
    }

    /** The fire times of a queue's jobs, in order, as minutes after the base. */
    private static List<Long> order(ScheduleQueue queue) {
        return queue.drainOrder().stream()
                .map(job -> java.time.Duration.between(BASE, job.nextFireAt()).toMinutes())
                .toList();
    }

    /** Polls the whole queue, returning fire times in the order they came out. */
    private static List<Long> pollAll(ScheduleQueue queue) {
        List<Long> polled = new ArrayList<>();
        while (!queue.isEmpty()) {
            Job job = queue.poll().orElseThrow();
            polled.add(java.time.Duration.between(BASE, job.nextFireAt()).toMinutes());
        }
        return polled;
    }

    // ---- basics -----------------------------------------------------------

    @Test("an empty queue has nothing to offer")
    static void emptyQueue() {
        ScheduleQueue queue = new ScheduleQueue();

        isTrue(queue.isEmpty(), "a new queue to be empty");
        equal(0, queue.size(), "the size");
        isTrue(queue.peek().isEmpty(), "a peek to return something");
        isTrue(queue.poll().isEmpty(), "a poll to return something");
        isTrue(queue.pollDue(BASE).isEmpty(), "a due poll to return something");
    }

    @Test("peek returns the earliest job without removing it")
    static void peekDoesNotRemove() {
        ScheduleQueue queue = new ScheduleQueue();
        queue.add(at(30));
        queue.add(at(10));
        queue.add(at(20));

        equal(BASE.plusMinutes(10), queue.peek().orElseThrow().nextFireAt(), "the earliest");
        equal(3, queue.size(), "the size after peeking");
    }

    @Test("polling drains in fire-time order")
    static void pollingIsOrdered() {
        ScheduleQueue queue = new ScheduleQueue();
        for (int minutes : new int[] {50, 10, 40, 20, 30}) {
            queue.add(at(minutes));
        }

        equal(List.of(10L, 20L, 30L, 40L, 50L), pollAll(queue), "the drained order");
    }

    @Test("adding the same job twice is rejected")
    static void duplicateAddIsRejected() {
        // Two heap entries for one job share a single position entry, so the
        // second is unreachable and the job fires twice per schedule.
        ScheduleQueue queue = new ScheduleQueue();
        Job job = at(10);
        queue.add(job);

        IllegalArgumentException error =
                throws_(IllegalArgumentException.class, () -> queue.add(job));
        contains(error.getMessage(), "already queued", "the message says why");
    }

    @Test("a null job is rejected")
    static void nullAddIsRejected() {
        throws_(IllegalArgumentException.class, () -> new ScheduleQueue().add(null));
    }

    // ---- due polling ------------------------------------------------------

    @Test("pollDue returns a job at its fire time, inclusively")
    static void pollDueIsInclusive() {
        // The scheduler ticks at the fire minute, so an exclusive comparison
        // would delay every job by one tick.
        ScheduleQueue queue = new ScheduleQueue();
        queue.add(at(10));

        isTrue(queue.pollDue(BASE.plusMinutes(9)).isEmpty(), "a job due a minute later");
        isTrue(queue.pollDue(BASE.plusMinutes(10)).isPresent(), "a job due exactly now");
    }

    @Test("pollDue leaves a job that is not yet due")
    static void pollDueLeavesFutureJobs() {
        ScheduleQueue queue = new ScheduleQueue();
        queue.add(at(60));

        isTrue(queue.pollDue(BASE).isEmpty(), "a job due in an hour");
        equal(1, queue.size(), "the queue to still hold it");
    }

    @Test("pollDue takes only the jobs that are due")
    static void pollDueTakesTheDueOnes() {
        ScheduleQueue queue = new ScheduleQueue();
        queue.add(at(5));
        queue.add(at(10));
        queue.add(at(90));

        int taken = 0;
        while (queue.pollDue(BASE.plusMinutes(30)).isPresent()) {
            taken++;
        }

        equal(2, taken, "the number of due jobs taken");
        equal(1, queue.size(), "the jobs left behind");
    }

    // ---- removal ----------------------------------------------------------

    @Test("a job can be removed from the middle")
    static void removeFromTheMiddle() {
        ScheduleQueue queue = new ScheduleQueue();
        Job target = at(30);
        for (int minutes : new int[] {10, 20, 40, 50}) {
            queue.add(at(minutes));
        }
        queue.add(target);

        isTrue(queue.remove(target.id()).isPresent(), "the removal to find the job");
        isFalse(queue.contains(target.id()), "the queue to still hold it");
        equal(List.of(10L, 20L, 40L, 50L), pollAll(queue), "what is left, in order");
    }

    @Test("removing the earliest job promotes the next")
    static void removeTheHead() {
        ScheduleQueue queue = new ScheduleQueue();
        Job first = at(10);
        queue.add(first);
        queue.add(at(20));

        queue.remove(first.id());

        equal(BASE.plusMinutes(20), queue.peek().orElseThrow().nextFireAt(), "the new head");
    }

    @Test("removing the last element does not disturb the heap")
    static void removeTheTail() {
        // The last slot is the one case removeAt short-circuits, so it gets
        // its own test rather than being assumed covered.
        ScheduleQueue queue = new ScheduleQueue();
        queue.add(at(10));
        queue.add(at(20));
        Job last = at(30);
        queue.add(last);

        queue.remove(last.id());

        equal(List.of(10L, 20L), pollAll(queue), "what is left");
    }

    @Test("removing a job that was never queued reports nothing")
    static void removeUnknownJob() {
        ScheduleQueue queue = new ScheduleQueue();
        queue.add(at(10));

        isTrue(queue.remove(JobId.generate()).isEmpty(), "the removal to find something");
        equal(1, queue.size(), "the queue to be disturbed");
    }

    @Test("removing every job empties the queue and its index")
    static void removeEverything() {
        ScheduleQueue queue = new ScheduleQueue();
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Job job = at(i * 7 % 20);
            jobs.add(job);
            queue.add(job);
        }

        for (Job job : jobs) {
            isTrue(queue.remove(job.id()).isPresent(), "each removal to succeed");
        }

        isTrue(queue.isEmpty(), "the queue to be empty");
        for (Job job : jobs) {
            isFalse(queue.contains(job.id()), "the index to still know " + job.id());
        }
    }

    // ---- rescheduling -----------------------------------------------------

    @Test("rescheduling a queued job moves it, keeping it scheduled")
    static void rescheduleMovesAJob() {
        // The bug this caught: rescheduleAt asked for a SCHEDULED ->
        // SCHEDULED transition, which the state machine rightly forbids, so
        // moving a queued job's time threw.
        ScheduleQueue queue = new ScheduleQueue();
        Job job = at(50);
        queue.add(at(10));
        queue.add(job);

        isTrue(queue.reschedule(job.id(), BASE.plusMinutes(5)), "the reschedule to find the job");
        equal(BASE.plusMinutes(5), queue.peek().orElseThrow().nextFireAt(), "the new head");
    }

    @Test("rescheduling later sinks a job down the heap")
    static void rescheduleLater() {
        ScheduleQueue queue = new ScheduleQueue();
        Job job = at(10);
        queue.add(job);
        queue.add(at(20));
        queue.add(at(30));

        queue.reschedule(job.id(), BASE.plusMinutes(99));

        equal(List.of(20L, 30L, 99L), pollAll(queue), "the order after sinking");
    }

    @Test("rescheduling earlier lifts a job to the head")
    static void rescheduleEarlier() {
        ScheduleQueue queue = new ScheduleQueue();
        queue.add(at(10));
        queue.add(at(20));
        Job job = at(30);
        queue.add(job);

        queue.reschedule(job.id(), BASE.plusMinutes(1));

        equal(List.of(1L, 10L, 20L), pollAll(queue), "the order after lifting");
    }

    @Test("rescheduling a job that is not queued reports false")
    static void rescheduleUnknownJob() {
        isFalse(new ScheduleQueue().reschedule(JobId.generate(), BASE), "an unknown reschedule");
    }

    // ---- ordering determinism --------------------------------------------

    @Test("jobs due at the same time come out in submission order")
    static void tiesBreakByCreationOrder() {
        // Every @daily job fires at midnight, so ties are the common case,
        // not the edge. Without a tiebreak their order depends on heap
        // mechanics and would differ on each restart.
        ScheduleQueue queue = new ScheduleQueue();
        JobId first = JobId.generate(1_700_000_000_000L);
        JobId second = JobId.generate(1_700_000_001_000L);
        JobId third = JobId.generate(1_700_000_002_000L);

        queue.add(at(10, third));
        queue.add(at(10, first));
        queue.add(at(10, second));

        List<JobId> drained = queue.drainOrder().stream().map(Job::id).toList();
        equal(List.of(first, second, third), drained, "the order of three simultaneous jobs");
    }

    // ---- the heap invariant ----------------------------------------------

    @Test("the head is the minimum after any sequence of operations")
    static void headIsAlwaysTheMinimum() {
        // A heap bug shows up as a job firing late rather than as an
        // exception, so the invariant is checked directly against a
        // brute-force minimum after every mutation.
        ScheduleQueue queue = new ScheduleQueue();
        Random random = new Random(20261028);
        List<Job> live = new ArrayList<>();

        for (int step = 0; step < 4_000; step++) {
            int action = random.nextInt(10);

            if (action < 5 || live.isEmpty()) {
                Job job = at(random.nextInt(500));
                queue.add(job);
                live.add(job);
            } else if (action < 7) {
                Job job = live.remove(random.nextInt(live.size()));
                queue.remove(job.id());
            } else if (action < 9) {
                Job job = live.get(random.nextInt(live.size()));
                queue.reschedule(job.id(), BASE.plusMinutes(random.nextInt(500)));
            } else {
                queue.poll().ifPresent(live::remove);
            }

            equal(live.size(), queue.size(), "the size at step " + step);

            if (!live.isEmpty()) {
                LocalDateTime expected = live.stream()
                        .map(Job::nextFireAt)
                        .min(LocalDateTime::compareTo)
                        .orElseThrow();
                equal(expected, queue.peek().orElseThrow().nextFireAt(),
                        "the head at step " + step);
            }
        }
    }

    @Test("a shuffled batch drains in order")
    static void shuffledBatchDrainsSorted() {
        ScheduleQueue queue = new ScheduleQueue();
        List<Integer> minutes = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            minutes.add(i);
        }
        Collections.shuffle(minutes, new Random(5));
        for (int minute : minutes) {
            queue.add(at(minute));
        }

        List<Long> drained = pollAll(queue);
        List<Long> sorted = new ArrayList<>(drained);
        Collections.sort(sorted);

        equal(sorted, drained, "500 shuffled jobs drained in order");
    }

    @Test("drainOrder does not disturb the queue")
    static void drainOrderIsReadOnly() {
        // Named for what it returns rather than what it does, so this pins
        // that it is a view: the heap is only partially ordered, and code
        // that read it as a sorted list would be wrong.
        ScheduleQueue queue = new ScheduleQueue();
        queue.add(at(30));
        queue.add(at(10));
        queue.add(at(20));

        equal(List.of(10L, 20L, 30L), order(queue), "the ordered view");
        equal(3, queue.size(), "the size afterwards");
        equal(List.of(10L, 20L, 30L), order(queue), "the same view again");
    }

    // ---- lookup and clearing ---------------------------------------------

    @Test("a queued job can be found by id")
    static void getById() {
        ScheduleQueue queue = new ScheduleQueue();
        Job job = at(10);
        queue.add(job);
        queue.add(at(20));

        equal(job.id(), queue.get(job.id()).orElseThrow().id(), "the job found by id");
        isTrue(queue.get(JobId.generate()).isEmpty(), "an unknown id to find a job");
    }

    @Test("clear empties the queue and its index")
    static void clearEmpties() {
        ScheduleQueue queue = new ScheduleQueue();
        Job job = at(10);
        queue.add(job);
        queue.add(at(20));

        queue.clear();

        isTrue(queue.isEmpty(), "the queue to be empty");
        isFalse(queue.contains(job.id()), "the index to still know the job");
        // Re-adding proves the index was cleared too, not just the heap.
        queue.add(job);
        equal(1, queue.size(), "the size after re-adding");
    }
}
