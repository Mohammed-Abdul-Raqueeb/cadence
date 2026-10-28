package dev.raqueeb.cadence;

/**
 * Version and build identity for the library.
 *
 * <p>Cadence is a job scheduler built on the JDK alone: a cron parser, a
 * priority queue keyed by next fire time, a worker pool, retries with
 * backoff, and an append-only journal so a restart does not lose state.
 *
 * <p>The no-dependency rule is the project's one hard constraint. It is worth
 * stating why, because it costs real work: a scheduler is the kind of thing
 * that ends up embedded in other people's services, and every dependency it
 * carries becomes a version conflict in somebody else's build. The cost is
 * paid in this repository rather than in theirs.
 */
public final class Cadence {

    /** The library version, following semantic versioning. */
    public static final String VERSION = "0.1.0";

    /** The name reported by the CLI and the journal header. */
    public static final String NAME = "cadence";

    private Cadence() {
    }

    /**
     * The name and version together, as the CLI prints it.
     *
     * @return for example {@code cadence 0.1.0}
     */
    public static String identity() {
        return NAME + " " + VERSION;
    }
}
