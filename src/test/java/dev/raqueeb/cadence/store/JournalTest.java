package dev.raqueeb.cadence.store;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import dev.raqueeb.cadence.job.JobId;
import dev.raqueeb.cadence.store.JournalEntry.Kind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Appending to the journal, and recovering from a damaged one. */
final class JournalTest {

    /** Runs {@code body} against a fresh temporary journal path, then deletes it. */
    private static void withTempJournal(JournalBody body) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("cadence-journal-test");
            body.run(directory.resolve("cadence.journal"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp file is not worth failing a test over.
                }
            });
        } catch (IOException ignored) {
            // Likewise.
        }
    }

    private static JournalEntry entry(Kind kind, JobId id, long at) {
        return JournalEntry.of(kind, id, at);
    }

    @FunctionalInterface
    private interface JournalBody {
        void run(Path path) throws Exception;
    }

    // ---- the entry record -------------------------------------------------

    @Test("an entry round-trips through JSON")
    static void entryRoundTrips() {
        JobId id = JobId.generate();
        JournalEntry original = new JournalEntry(Kind.FAILED, id, 1_793_000_000_000L,
                Map.of("error", "connection refused", "willRetry", Boolean.TRUE));

        JournalEntry parsed = JournalEntry.fromJson(original.toJson());

        equal(original.kind(), parsed.kind(), "the kind");
        equal(original.jobId(), parsed.jobId(), "the job id");
        equal(original.atMillis(), parsed.atMillis(), "the timestamp");
        equal("connection refused", parsed.string("error"), "the error field");
        isTrue(parsed.flag("willRetry", false), "the retry flag");
    }

    @Test("the record's own fields cannot be shadowed by payload fields")
    static void payloadCannotShadowTheEnvelope() {
        // `data` is nested rather than merged, so a payload field called
        // `kind` describes the payload and not the record.
        JournalEntry original = new JournalEntry(Kind.STARTED, JobId.generate(), 1L,
                Map.of("kind", "not-a-kind", "job", "not-a-job"));

        JournalEntry parsed = JournalEntry.fromJson(original.toJson());

        equal(Kind.STARTED, parsed.kind(), "the record's kind");
        equal("not-a-kind", parsed.string("kind"), "the payload's kind field");
    }

    @Test("an entry needs a kind and a job id")
    static void entryRequiresKindAndId() {
        throws_(IllegalArgumentException.class,
                () -> new JournalEntry(null, JobId.generate(), 1L, Map.of()));
        throws_(IllegalArgumentException.class,
                () -> new JournalEntry(Kind.STARTED, null, 1L, Map.of()));
    }

    @Test("a null data map becomes empty rather than a later NullPointerException")
    static void nullDataBecomesEmpty() {
        JournalEntry entry = new JournalEntry(Kind.STARTED, JobId.generate(), 1L, null);

        equal(Map.of(), entry.data(), "the data map");
        equal(null, entry.string("anything"), "a missing field");
        equal(7L, entry.number("anything", 7L), "a missing number's fallback");
    }

    @Test("a record missing a required field is rejected as corrupt")
    static void malformedEntryIsRejected() {
        throws_(JsonParseException.class, () -> JournalEntry.fromJson("{\"kind\":\"started\"}"));
        throws_(JsonParseException.class,
                () -> JournalEntry.fromJson("{\"kind\":\"nope\",\"job\":\"0000000000000000\","
                        + "\"at\":1}"));
    }

    @Test("every kind survives its wire name")
    static void kindsRoundTrip() {
        for (Kind kind : Kind.values()) {
            equal(kind, Kind.fromWireName(kind.wireName()), "the kind " + kind);
        }
        throws_(IllegalArgumentException.class, () -> Kind.fromWireName("exploded"));
    }

    // ---- appending and replaying -----------------------------------------

    @Test("a fresh journal gets a header")
    static void freshJournalHasAHeader() {
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));
            }

            List<String> lines = Files.readAllLines(path);
            equal("cadence-journal 1", lines.get(0), "the first line");
            equal(2, lines.size(), "the number of lines");
        });
    }

    @Test("records replay in the order they were written")
    static void replayPreservesOrder() {
        withTempJournal(path -> {
            List<JobId> ids = new ArrayList<>();
            try (Journal journal = Journal.open(path)) {
                for (int i = 0; i < 50; i++) {
                    JobId id = JobId.generate(1_700_000_000_000L + i);
                    ids.add(id);
                    journal.append(entry(Kind.SUBMITTED, id, 1_700_000_000_000L + i));
                }
            }

            Journal.Replay replay = Journal.replay(path);

            equal(50, replay.entries().size(), "the number of records");
            equal(ids, replay.entries().stream().map(JournalEntry::jobId).toList(),
                    "the order of the records");
            isFalse(replay.isDamaged(), "the journal to be damaged");
        });
    }

    @Test("reopening a journal appends rather than truncating")
    static void reopeningAppends() {
        // Getting this wrong loses the entire history on every restart, which
        // is the one failure a journal exists to prevent.
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));
            }
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.STARTED, JobId.generate(), 2L));
            }

            Journal.Replay replay = Journal.replay(path);

            equal(2, replay.entries().size(), "the records after reopening");
            equal(Kind.SUBMITTED, replay.entries().get(0).kind(), "the first record");
            equal(Kind.STARTED, replay.entries().get(1).kind(), "the second record");
        });
    }

    @Test("a record is readable the instant append returns")
    static void appendIsVisibleImmediately() {
        // Each append flushes. Without that the record sits in this process's
        // buffer, so a crash loses records the caller was told were written.
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));

                equal(1, Journal.replay(path).entries().size(), "the records visible mid-session");
            }
        });
    }

    @Test("replaying a journal that does not exist is a first start, not an error")
    static void missingJournalIsNotAnError() {
        withTempJournal(path -> {
            Journal.Replay replay = Journal.replay(path.resolveSibling("never-written.journal"));

            equal(0, replay.entries().size(), "the records");
            isFalse(replay.isDamaged(), "a missing journal to be damaged");
        });
    }

    @Test("appending to a closed journal is rejected")
    static void appendAfterCloseIsRejected() {
        withTempJournal(path -> {
            Journal journal = Journal.open(path);
            journal.close();

            IllegalStateException error = throws_(IllegalStateException.class,
                    () -> journal.append(entry(Kind.STARTED, JobId.generate(), 1L)));
            contains(error.getMessage(), "closed", "the message says the journal is closed");
        });
    }

    @Test("closing twice is harmless")
    static void doubleCloseIsFine() {
        withTempJournal(path -> {
            Journal journal = Journal.open(path);
            journal.close();
            journal.close();
        });
    }

    @Test("a null entry or path is rejected")
    static void nullsAreRejected() {
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                throws_(IllegalArgumentException.class, () -> journal.append(null));
            }
        });
        throws_(IllegalArgumentException.class, () -> Journal.open(null));
        throws_(IllegalArgumentException.class, () -> Journal.replay(null));
    }

    @Test("syncOnAppend writes the same records, just more slowly")
    static void syncOnAppendStillWorks() {
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path, true)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));
                journal.append(entry(Kind.STARTED, JobId.generate(), 2L));
            }

            equal(2, Journal.replay(path).entries().size(), "the records");
        });
    }

    // ---- recovery ---------------------------------------------------------

    @Test("an incomplete final record is dropped and reported as a truncated tail")
    static void truncatedTailIsRecovered() {
        // The ordinary signature of a process killed between the write and
        // the flush: recoverable, expected, and not worth alarming anyone
        // about.
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));
                journal.append(entry(Kind.STARTED, JobId.generate(), 2L));
            }

            String whole = Files.readString(path, StandardCharsets.UTF_8);
            Files.writeString(path, whole.substring(0, whole.length() - 20),
                    StandardCharsets.UTF_8);

            Journal.Replay replay = Journal.replay(path);

            equal(1, replay.entries().size(), "the intact records");
            isTrue(replay.truncatedTail(), "the tail to be reported as truncated");
            isFalse(replay.isDamaged(), "a truncated tail to be reported as damage");
            contains(replay.summary(), "incomplete final record", "the summary");
        });
    }

    @Test("a checksum mismatch mid-file stops the replay and is reported as damage")
    static void midFileDamageStopsReplay() {
        // The bytes changed after they were written, so the records after it
        // cannot be vouched for either.
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                for (int i = 0; i < 5; i++) {
                    journal.append(entry(Kind.SUBMITTED, JobId.generate(), i));
                }
            }

            List<String> lines = new ArrayList<>(Files.readAllLines(path));
            // Flip a character inside the third record's JSON, leaving the
            // line complete and well-formed. Only the CRC can catch this.
            String damaged = lines.get(3);
            lines.set(3, damaged.substring(0, 9) + damaged.substring(9).replace("submitted",
                    "cancelled"));
            Files.write(path, lines, StandardCharsets.UTF_8);

            Journal.Replay replay = Journal.replay(path);

            equal(2, replay.entries().size(), "the records before the damage");
            isTrue(replay.isDamaged(), "the journal to be reported as damaged");
            contains(replay.damage(), "checksum", "the damage description");
            contains(replay.damage(), "line 4", "the damaged line number");
        });
    }

    @Test("a checksum catches damage the JSON parser would not")
    static void checksumCatchesValueDamage() {
        // Why the CRC is there at all: a flipped digit inside a number parses
        // perfectly and yields a wrong timestamp. The parser guards the
        // structure; the checksum guards the values.
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1_793_000_000_000L));
                journal.append(entry(Kind.STARTED, JobId.generate(), 1_793_000_000_001L));
            }

            List<String> lines = new ArrayList<>(Files.readAllLines(path));
            String record = lines.get(1);
            lines.set(1, record.replace("1793000000000", "1793000009000"));
            Files.write(path, lines, StandardCharsets.UTF_8);

            Journal.Replay replay = Journal.replay(path);

            isTrue(replay.isDamaged(), "a flipped digit to be caught");
            equal(0, replay.entries().size(), "the records read before the damage");
        });
    }

    @Test("a file that is not a journal is rejected by its header")
    static void foreignFileIsRejected() {
        withTempJournal(path -> {
            Files.writeString(path, "I am not a journal\nnor is this\n", StandardCharsets.UTF_8);

            IllegalStateException error =
                    throws_(IllegalStateException.class, () -> Journal.replay(path));
            contains(error.getMessage(), "does not look like a cadence journal", "the message");
        });
    }

    @Test("an empty file replays as empty rather than failing its header check")
    static void emptyFileIsNotAFailure() {
        withTempJournal(path -> {
            Files.writeString(path, "", StandardCharsets.UTF_8);

            equal(0, Journal.replay(path).entries().size(), "the records");
        });
    }

    @Test("blank lines are skipped rather than counted as damage")
    static void blankLinesAreSkipped() {
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));
            }
            Files.writeString(path, "\n\n", StandardOpenOption.APPEND);

            Journal.Replay replay = Journal.replay(path);

            equal(1, replay.entries().size(), "the records");
            isFalse(replay.isDamaged(), "blank lines to be damage");
        });
    }

    // ---- rewriting --------------------------------------------------------

    @Test("a rewrite keeps only the records given")
    static void rewriteCompacts() {
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                for (int i = 0; i < 10; i++) {
                    journal.append(entry(Kind.STARTED, JobId.generate(), i));
                }
            }

            List<JournalEntry> keep = Journal.replay(path).entries().subList(0, 3);
            Journal.rewrite(path, keep);

            Journal.Replay replay = Journal.replay(path);
            equal(3, replay.entries().size(), "the records after compaction");
            isFalse(replay.isDamaged(), "the rewritten journal to be damaged");
        });
    }

    @Test("a rewritten journal can be appended to")
    static void rewriteLeavesAnAppendableFile() {
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));
            }

            Journal.rewrite(path, Journal.replay(path).entries());

            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.STARTED, JobId.generate(), 2L));
            }

            equal(2, Journal.replay(path).entries().size(), "the records after appending");
        });
    }

    @Test("a rewrite leaves no temporary file behind")
    static void rewriteCleansUp() {
        // It writes to a sibling .tmp and renames, so a crash leaves either
        // the old journal or the new one. A leftover .tmp on success would
        // mean the rename did not happen.
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));
            }

            Journal.rewrite(path, Journal.replay(path).entries());

            isFalse(Files.exists(path.resolveSibling(path.getFileName() + ".tmp")),
                    "a leftover temporary file");
        });
    }

    @Test("a journal reports its size")
    static void reportsItsSize() {
        withTempJournal(path -> {
            try (Journal journal = Journal.open(path)) {
                long empty = journal.sizeBytes();
                journal.append(entry(Kind.SUBMITTED, JobId.generate(), 1L));

                isTrue(journal.sizeBytes() > empty, "the journal to grow when appended to");
                equal(path, journal.path(), "the reported path");
            }
        });
    }
}
