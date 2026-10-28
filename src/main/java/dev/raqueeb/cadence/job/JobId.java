package dev.raqueeb.cadence.job;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A job's identity: sortable, printable and unique.
 *
 * <p>The text is sixteen characters of Crockford base32 — thirteen encoding a
 * millisecond timestamp, three a counter and random tail. Two properties come
 * out of that shape and both are used:
 *
 * <ul>
 *   <li><b>Lexicographic order is creation order.</b> Sorting ids sorts jobs
 *       by when they were submitted, so the journal needs no separate sequence
 *       column and {@code cadence list} comes out oldest-first for free.
 *   <li><b>No hyphens or mixed case.</b> Ids end up in log lines, URLs and
 *       shell arguments. A UUID's hyphens make double-clicking a log line
 *       select half of it.
 * </ul>
 *
 * <p>Crockford base32 omits I, L, O and U, so an id cannot be misread between
 * one and ell, or zero and oh, when someone copies it out of a terminal by
 * hand — and cannot accidentally spell a word.
 *
 * @param text the encoded identifier
 */
public record JobId(String text) implements Comparable<JobId> {

    /** Crockford's alphabet: no I, L, O or U. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /** Characters spent on the timestamp. 13 base32 digits cover 65 bits. */
    private static final int TIME_CHARS = 13;

    /** Characters spent on the counter and random tail. */
    private static final int TAIL_CHARS = 3;

    /** The total length of an encoded id. */
    public static final int LENGTH = TIME_CHARS + TAIL_CHARS;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Distinguishes ids minted in the same millisecond.
     *
     * <p>The random tail alone would collide roughly once in a thousand for
     * two jobs submitted in the same millisecond, which a bulk submit does
     * routinely. The counter makes that ordering total within a process.
     */
    private static final AtomicLong COUNTER = new AtomicLong(RANDOM.nextInt(1 << 10));

    /**
     * Validates the text.
     *
     * @throws IllegalArgumentException when the text is not a well-formed id
     */
    public JobId {
        if (text == null || text.length() != LENGTH) {
            throw new IllegalArgumentException(
                    "a job id is " + LENGTH + " characters, got " + render(text));
        }
        for (int i = 0; i < text.length(); i++) {
            if (indexOf(text.charAt(i)) < 0) {
                throw new IllegalArgumentException("a job id cannot contain '" + text.charAt(i)
                        + "'; the alphabet is " + new String(ALPHABET));
            }
        }
    }

    /**
     * Mints an id for a job created now.
     *
     * @return a fresh identifier
     */
    public static JobId generate() {
        return generate(System.currentTimeMillis());
    }

    /**
     * Mints an id for a job created at {@code epochMillis}.
     *
     * <p>Taking the time as a parameter is what lets a test assert the
     * ordering property without sleeping between calls.
     *
     * @param epochMillis the creation time, in milliseconds since the epoch
     * @return a fresh identifier whose timestamp is {@code epochMillis}
     */
    public static JobId generate(long epochMillis) {
        if (epochMillis < 0) {
            throw new IllegalArgumentException(
                    "a job cannot be created before the epoch: " + epochMillis);
        }

        char[] out = new char[LENGTH];

        // Most significant digit first, so the text sorts the way the number
        // does.
        long remaining = epochMillis;
        for (int i = TIME_CHARS - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (remaining & 31)];
            remaining >>>= 5;
        }

        long tail = COUNTER.incrementAndGet() & ((1L << (TAIL_CHARS * 5)) - 1);
        for (int i = LENGTH - 1; i >= TIME_CHARS; i--) {
            out[i] = ALPHABET[(int) (tail & 31)];
            tail >>>= 5;
        }

        return new JobId(new String(out));
    }

    /**
     * Parses an id, accepting lower case and the characters Crockford treats
     * as interchangeable.
     *
     * <p>{@code I} and {@code L} are read as {@code 1} and {@code O} as
     * {@code 0}, which is the point of the alphabet: an id copied by hand out
     * of a terminal still resolves.
     *
     * @param text the id as typed
     * @return the parsed id
     * @throws IllegalArgumentException when the text cannot be an id
     */
    public static JobId parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("a job id cannot be null");
        }

        String trimmed = text.trim().toUpperCase();
        StringBuilder canonical = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            canonical.append(switch (c) {
                case 'I', 'L' -> '1';
                case 'O' -> '0';
                default -> c;
            });
        }

        return new JobId(canonical.toString());
    }

    /**
     * The creation time encoded in the id.
     *
     * @return milliseconds since the epoch
     */
    public long createdAtMillis() {
        long value = 0;
        for (int i = 0; i < TIME_CHARS; i++) {
            value = (value << 5) | indexOf(text.charAt(i));
        }
        return value;
    }

    /**
     * A short prefix for log lines, where the whole id is more than is needed.
     *
     * @return the last six characters, which carry the entropy
     */
    public String shortForm() {
        return text.substring(LENGTH - 6);
    }

    @Override
    public int compareTo(JobId other) {
        return text.compareTo(other.text);
    }

    @Override
    public String toString() {
        return text;
    }

    /** The value of a character, or -1 when it is not in the alphabet. */
    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /** Renders a rejected value for an error message. */
    private static String render(String text) {
        return text == null ? "null" : "\"" + text + "\" (" + text.length() + " characters)";
    }
}
