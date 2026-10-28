package dev.raqueeb.cadence.queue;

import dev.raqueeb.cadence.job.Job;
import dev.raqueeb.cadence.job.JobId;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jobs ordered by when they next fire.
 *
 * <p>A binary min-heap over an {@link ArrayList}, with a side map from job id
 * to heap position. Both halves are needed:
 *
 * <ul>
 *   <li>The heap answers "what fires next" in constant time and reorders in
 *       logarithmic time, which is what the scheduler loop asks on every tick.
 *   <li>The side map makes {@link #remove} and {@link #reschedule}
 *       logarithmic rather than linear. Without it, cancelling a job means
 *       scanning the heap, and a scheduler holding ten thousand jobs scans
 *       ten thousand entries to cancel one.
 * </ul>
 *
 * <p>{@link java.util.PriorityQueue} was the obvious alternative and is not
 * used for exactly that reason: its {@code remove(Object)} is linear, and it
 * offers no way to say "this element's key changed, fix the heap" — the only
 * route is remove-then-add, which is two linear scans. Rescheduling happens
 * once per fire, so that cost would land on the hot path.
 *
 * <p>Not thread-safe. The scheduler owns the lock.
 */
public final class ScheduleQueue {

    /** The heap. Element 0 is the next job to fire. */
    private final List<Job> heap = new ArrayList<>();

    /** Where each job sits in {@link #heap}, so it can be found without a scan. */
    private final Map<JobId, Integer> positions = new HashMap<>();

    /** Creates an empty queue. */
    public ScheduleQueue() {
    }

    /**
     * Adds a job.
     *
     * @param job the job to schedule
     * @throws IllegalArgumentException when the job is already queued
     */
    public void add(Job job) {
        if (job == null) {
            throw new IllegalArgumentException("cannot queue a null job");
        }
        if (positions.containsKey(job.id())) {
            // Adding a job twice would leave two heap entries pointing at one
            // job and one position entry, so the second would be unreachable
            // and the job would fire twice per schedule.
            throw new IllegalArgumentException("job " + job.id() + " is already queued");
        }

        heap.add(job);
        int index = heap.size() - 1;
        positions.put(job.id(), index);
        siftUp(index);
    }

    /**
     * The next job to fire, without removing it.
     *
     * @return the earliest job, or empty when the queue is empty
     */
    public Optional<Job> peek() {
        return heap.isEmpty() ? Optional.empty() : Optional.of(heap.get(0));
    }

    /**
     * The next job to fire, removing it.
     *
     * @return the earliest job, or empty when the queue is empty
     */
    public Optional<Job> poll() {
        if (heap.isEmpty()) {
            return Optional.empty();
        }

        Job first = heap.get(0);
        removeAt(0);
        return Optional.of(first);
    }

    /**
     * Takes the next job if it is due at or before {@code now}.
     *
     * <p>The scheduler's main question, in one call: polling and then putting
     * the job back when it turns out not to be due costs two heap operations
     * and briefly leaves the queue in a state where the job is missing.
     *
     * @param now the current time
     * @return the job, or empty when nothing is due yet
     */
    public Optional<Job> pollDue(LocalDateTime now) {
        if (heap.isEmpty() || heap.get(0).nextFireAt().isAfter(now)) {
            return Optional.empty();
        }
        return poll();
    }

    /**
     * Removes a job by id.
     *
     * @param id the job to remove
     * @return the removed job, or empty when it was not queued
     */
    public Optional<Job> remove(JobId id) {
        Integer index = positions.get(id);
        if (index == null) {
            return Optional.empty();
        }

        Job removed = heap.get(index);
        removeAt(index);
        return Optional.of(removed);
    }

    /**
     * Moves a queued job to a new fire time.
     *
     * @param id   the job to move
     * @param when its new fire time
     * @return true when the job was queued and has been moved
     */
    public boolean reschedule(JobId id, LocalDateTime when) {
        Integer index = positions.get(id);
        if (index == null) {
            return false;
        }

        Job job = heap.get(index);
        job.rescheduleAt(when);

        // The key moved in an unknown direction, so both sifts are attempted.
        // Each is a no-op when the heap property already holds in that
        // direction, so this is still one logarithmic pass, not two.
        siftUp(index);
        siftDown(positions.get(id));
        return true;
    }

    /**
     * Whether a job is queued.
     *
     * @param id the job to look for
     * @return true when the job is in the queue
     */
    public boolean contains(JobId id) {
        return positions.containsKey(id);
    }

    /**
     * A queued job by id.
     *
     * @param id the job to look for
     * @return the job, or empty when it is not queued
     */
    public Optional<Job> get(JobId id) {
        Integer index = positions.get(id);
        return index == null ? Optional.empty() : Optional.of(heap.get(index));
    }

    /**
     * How many jobs are queued.
     *
     * @return the queue size
     */
    public int size() {
        return heap.size();
    }

    /**
     * Whether the queue holds nothing.
     *
     * @return true when empty
     */
    public boolean isEmpty() {
        return heap.isEmpty();
    }

    /** Empties the queue. */
    public void clear() {
        heap.clear();
        positions.clear();
    }

    /**
     * Every queued job, in fire-time order.
     *
     * <p>Sorted on each call, so this is for {@code cadence list} and tests
     * rather than for the scheduler loop. The heap itself is only partially
     * ordered — element 0 is the minimum, but the rest is not a sorted list,
     * and code that assumed otherwise would read the queue wrongly.
     *
     * @return a new list, ordered by fire time then by id
     */
    public List<Job> drainOrder() {
        List<Job> ordered = new ArrayList<>(heap);
        ordered.sort(ScheduleQueue::compare);
        return ordered;
    }

    /**
     * Orders two jobs by fire time, breaking ties by id.
     *
     * <p>The tiebreak is what makes the queue deterministic. Jobs scheduled
     * for the same minute are common — every {@code @daily} job fires at
     * midnight — and without a tiebreak their order depends on heap
     * mechanics, so the same set of jobs would run in a different order on
     * each restart. Ids sort by creation time, so the tiebreak is
     * first-submitted-first.
     */
    private static int compare(Job left, Job right) {
        int byTime = left.nextFireAt().compareTo(right.nextFireAt());
        return byTime != 0 ? byTime : left.id().compareTo(right.id());
    }

    /** Removes the element at {@code index}, keeping the heap valid. */
    private void removeAt(int index) {
        int last = heap.size() - 1;
        Job removed = heap.get(index);

        if (index == last) {
            heap.remove(last);
            positions.remove(removed.id());
            return;
        }

        // The last element is moved into the hole and then sifted into place,
        // which is what keeps removal logarithmic.
        Job moved = heap.get(last);
        heap.set(index, moved);
        positions.put(moved.id(), index);
        heap.remove(last);
        positions.remove(removed.id());

        siftUp(index);
        siftDown(positions.get(moved.id()));
    }

    private void siftUp(int start) {
        int index = start;
        Job job = heap.get(index);

        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (compare(job, heap.get(parent)) >= 0) {
                break;
            }
            move(heap.get(parent), index);
            index = parent;
        }

        place(job, index);
    }

    private void siftDown(int start) {
        int index = start;
        Job job = heap.get(index);
        int half = heap.size() >>> 1;

        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;
            if (right < heap.size() && compare(heap.get(right), heap.get(child)) < 0) {
                child = right;
            }
            if (compare(job, heap.get(child)) <= 0) {
                break;
            }
            move(heap.get(child), index);
            index = child;
        }

        place(job, index);
    }

    /** Writes a job into a slot, keeping the position map in step. */
    private void move(Job job, int index) {
        heap.set(index, job);
        positions.put(job.id(), index);
    }

    /** Settles a job into its final slot. */
    private void place(Job job, int index) {
        heap.set(index, job);
        positions.put(job.id(), index);
    }
}
