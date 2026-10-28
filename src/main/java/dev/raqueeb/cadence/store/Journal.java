package dev.raqueeb.cadence.store;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * An append-only log of what happened to each job.
 *
 * <p>One record per line:
 *
 * <pre>
 *   cadence-journal 1
 *   a3f9c012 {"kind":"submitted","job":"00001M61FS6AA0QV","at":1793000000000}
 *   7b2e4d81 {"kind":"started","job":"00001M61FS6AA0QV","at":1793000060000}
 * </pre>
 *
 * <p>The eight hex digits are a CRC32 of the JSON that follows. Two kinds of
 * damage are possible and they are reported differently because they call for
 * different responses:
 *
 * <ul>
 *   <li><b>A truncated tail.</b> The signature of a process killed between
 *       the write and the flush. The last line is incomplete, every line
 *       before it is intact, and recovery is to drop the partial record.
 *       Normal, and not worth alarming anyone about.
 *   <li><b>A checksum mismatch on a complete line.</b> The bytes changed
 *       after they were written, which means the storage is damaged rather
 *       than the process interrupted. Replay stops there rather than
 *       continuing, because the records after it cannot be trusted either.
 * </ul>
 *
 * <p>Why a checksum at all, when the JSON parser would reject most damage
 * already: a flipped digit inside a number parses perfectly and yields a
 * wrong timestamp. The parser catches damage to the structure; the CRC
 * catches damage to the values.
 *
 * <p>Not thread-safe. The scheduler appends under its own lock, which it
 * holds anyway while changing the state the record describes — and that
 * ordering matters more than the locking does, since a record written
 * outside the lock could land out of order with the state it describes.
 */
public final class Journal implements AutoCloseable {

    /** The first line of every journal file. */
    static final String HEADER = "cadence-journal 1";

    private final Path path;
    private final boolean syncOnAppend;
    private BufferedWriter writer;

    private Journal(Path path, boolean syncOnAppend) {
        this.path = path;
        this.syncOnAppend = syncOnAppend;
    }

    /**
     * Opens a journal for appending, creating it if it does not exist.
     *
     * @param path         the journal file
     * @param syncOnAppend whether to force each record to disk before
     *     returning. Durable but slow — a sync is milliseconds, so this
     *     bounds the scheduler to a few hundred records a second. Off by
     *     default: losing the last few records to a power cut costs a repeated
     *     job run, which the handlers are expected to tolerate anyway.
     * @return the open journal
     * @throws UncheckedIOException when the file cannot be opened
     */
    public static Journal open(Path path, boolean syncOnAppend) {
        if (path == null) {
            throw new IllegalArgumentException("a journal needs a path");
        }

        Journal journal = new Journal(path, syncOnAppend);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            boolean fresh = !Files.exists(path) || Files.size(path) == 0;
            journal.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            if (fresh) {
                journal.writer.write(HEADER);
                journal.writer.newLine();
                journal.writer.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the journal at " + path, e);
        }
        return journal;
    }

    /**
     * Opens a journal without forcing each record to disk.
     *
     * @param path the journal file
     * @return the open journal
     */
    public static Journal open(Path path) {
        return open(path, false);
    }

    /**
     * Appends a record.
     *
     * @param entry what happened
     * @throws UncheckedIOException when the record cannot be written
     */
    public void append(JournalEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("cannot journal a null entry");
        }
        if (writer == null) {
            throw new IllegalStateException("the journal at " + path + " is closed");
        }

        String json = entry.toJson();
        try {
            writer.write(checksum(json));
            writer.write(' ');
            writer.write(json);
            writer.newLine();
            // Flushed every time regardless of syncOnAppend. Without it the
            // record sits in a buffer this process owns, so a crash loses
            // records that the caller has already been told were written --
            // and a reader opening the file concurrently would not see them.
            writer.flush();

            if (syncOnAppend) {
                syncToDisk();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to the journal at " + path, e);
        }
    }

    /** Forces the file's bytes out of the OS cache and onto the device. */
    private void syncToDisk() throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * Reads every intact record, in the order they were written.
     *
     * @param path the journal file
     * @return the replay result, holding the records and what was found wrong
     * @throws UncheckedIOException when the file cannot be read
     */
    public static Replay replay(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("a journal needs a path");
        }
        if (!Files.exists(path)) {
            // A missing journal is a first start, not an error.
            return new Replay(List.of(), 0, false, null);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the journal at " + path, e);
        }

        if (lines.isEmpty()) {
            return new Replay(List.of(), 0, false, null);
        }
        if (!HEADER.equals(lines.get(0))) {
            throw new IllegalStateException(path + " does not look like a cadence journal;"
                    + " its first line is \"" + abbreviate(lines.get(0)) + "\" rather than \""
                    + HEADER + "\"");
        }

        List<JournalEntry> entries = new ArrayList<>();
        int skipped = 0;
        boolean truncatedTail = false;
        String damage = null;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }

            boolean isLastLine = i == lines.size() - 1;
            String problem = problemWith(line);

            if (problem == null) {
                entries.add(JournalEntry.fromJson(line.substring(9)));
                continue;
            }

            if (isLastLine) {
                // An interrupted append. Expected, recoverable, and not worth
                // reporting as corruption.
                truncatedTail = true;
                skipped++;
                break;
            }

            // Damage in the middle means the bytes changed after they were
            // written. Everything after it is suspect too, so replay stops
            // rather than carrying on with records it cannot vouch for.
            damage = "line " + (i + 1) + ": " + problem;
            skipped++;
            break;
        }

        return new Replay(List.copyOf(entries), skipped, truncatedTail, damage);
    }

    /**
     * What is wrong with a line, or null when it is intact.
     *
     * <p>Returns a description rather than a boolean so the caller can say
     * what it found, which is the difference between a useful recovery
     * message and "the journal is corrupt".
     */
    private static String problemWith(String line) {
        if (line.length() < 10 || line.charAt(8) != ' ') {
            return "expected eight hex digits and a space, found \"" + abbreviate(line) + "\"";
        }

        String declared = line.substring(0, 8);
        String json = line.substring(9);
        String actual = checksum(json);

        if (!declared.equalsIgnoreCase(actual)) {
            return "checksum " + declared + " does not match the record's " + actual;
        }

        try {
            JournalEntry.fromJson(json);
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        return null;
    }

    /** The CRC32 of a record's JSON, as eight lower-case hex digits. */
    private static String checksum(String json) {
        CRC32 crc = new CRC32();
        crc.update(json.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    private static String abbreviate(String line) {
        return line.length() <= 24 ? line : line.substring(0, 24) + "...";
    }

    /**
     * Rewrites the journal with only the records given.
     *
     * <p>Written to a temporary file and renamed into place, because a rewrite
     * is the one operation that can lose the whole journal rather than its
     * tail. A rename within a directory is atomic, so a crash leaves either
     * the old journal or the new one, never a half-written file.
     *
     * @param path    the journal file
     * @param entries the records to keep
     * @throws UncheckedIOException when the file cannot be written
     */
    public static void rewrite(Path path, List<JournalEntry> entries) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                out.write(HEADER);
                out.newLine();
                for (JournalEntry entry : entries) {
                    String json = entry.toJson();
                    out.write(checksum(json));
                    out.write(' ');
                    out.write(json);
                    out.newLine();
                }
            }

            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rewrite the journal at " + path, e);
        }
    }

    /**
     * How many bytes the journal occupies.
     *
     * @return the file size, or 0 when the file does not exist
     */
    public long sizeBytes() {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot size the journal at " + path, e);
        }
    }

    /**
     * The journal's file.
     *
     * @return the path
     */
    public Path path() {
        return path;
    }

    @Override
    public void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot close the journal at " + path, e);
        } finally {
            writer = null;
        }
    }

    /**
     * What a replay found.
     *
     * @param entries       the intact records, in write order
     * @param skipped       how many records were unreadable
     * @param truncatedTail whether the last record was incomplete, which is
     *     the ordinary signature of an interrupted append
     * @param damage        a description of damage found mid-file, or null
     */
    public record Replay(List<JournalEntry> entries, int skipped, boolean truncatedTail,
            String damage) {

        /**
         * Whether the journal was damaged in a way that is not just an
         * interrupted append.
         *
         * @return true when a complete record failed its checksum
         */
        public boolean isDamaged() {
            return damage != null;
        }

        /**
         * A line describing the replay, for a startup log.
         *
         * @return a human-readable summary
         */
        public String summary() {
            StringBuilder out = new StringBuilder();
            out.append("replayed ").append(entries.size()).append(" records");
            if (truncatedTail) {
                out.append(", dropped an incomplete final record");
            }
            if (damage != null) {
                out.append(", stopped at damage: ").append(damage);
            }
            return out.toString();
        }
    }
}
