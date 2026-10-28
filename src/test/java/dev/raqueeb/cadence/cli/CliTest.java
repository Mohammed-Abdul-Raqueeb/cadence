package dev.raqueeb.cadence.cli;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import dev.raqueeb.cadence.job.JobId;
import dev.raqueeb.cadence.store.Journal;
import dev.raqueeb.cadence.store.JournalEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Argument parsing, and each command run against captured streams. */
final class CliTest {

    /** What a command printed and what it exited with. */
    private record Result(int code, String out, String err) {

        boolean succeeded() {
            return code == 0;
        }
    }

    /** Runs a command with its output captured rather than printed. */
    private static Result run(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = Main.run(argv, outStream, errStream);
        }

        return new Result(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    // ---- argument parsing -------------------------------------------------

    @Test("a bare command parses with no options")
    static void bareCommand() {
        Args args = Args.parse(new String[] {"next"});

        equal("next", args.command(), "the command");
        isTrue(args.positional().isEmpty(), "the positional arguments to be present");
    }

    @Test("no arguments at all gives an empty command")
    static void noArguments() {
        equal("", Args.parse(new String[0]).command(), "the command");
    }

    @Test("positional arguments keep their order")
    static void positionalOrder() {
        Args args = Args.parse(new String[] {"replay", "first", "second"});

        equal(List.of("first", "second"), args.positional(), "the positional arguments");
        equal("first", args.positional(0).orElseThrow(), "the first");
        isTrue(args.positional(9).isEmpty(), "a positional beyond the end to be present");
        isTrue(args.positional(-1).isEmpty(), "a negative index to find something");
    }

    @Test("an option takes the next argument as its value")
    static void optionWithSpace() {
        Args args = Args.parse(new String[] {"next", "--count", "8"});

        equal("8", args.option("count").orElseThrow(), "the option value");
        equal(8, args.intOption("count", 5), "the option as an integer");
    }

    @Test("an option can be written with an equals sign")
    static void optionWithEquals() {
        Args args = Args.parse(new String[] {"next", "--count=8"});

        equal("8", args.option("count").orElseThrow(), "the option value");
    }

    @Test("a value containing an equals sign survives")
    static void valueWithEquals() {
        // Payloads and query strings both contain them, so only the first
        // equals separates the name from the value.
        Args args = Args.parse(new String[] {"demo", "--payload=a=b=c"});

        equal("a=b=c", args.option("payload").orElseThrow(), "the option value");
    }

    @Test("an option followed by another option is a flag, not a value")
    static void optionNotFollowedByValue() {
        // Without this, `--json --count 3` would make "--count" the value of
        // --json and lose the count entirely.
        Args args = Args.parse(new String[] {"next", "--json", "--count", "3"});

        isTrue(args.flag("json"), "json to be a flag");
        equal(3, args.intOption("count", 0), "the count to survive");
    }

    @Test("a trailing option is a flag")
    static void trailingFlag() {
        Args args = Args.parse(new String[] {"next", "--verbose"});

        isTrue(args.flag("verbose"), "verbose to be set");
        isFalse(args.flag("quiet"), "quiet to be set");
    }

    @Test("a flag given a value still counts as present")
    static void flagWithValue() {
        isTrue(Args.parse(new String[] {"next", "--json=true"}).flag("json"),
                "json to be set when given a value");
    }

    @Test("a missing integer option falls back")
    static void intOptionFallback() {
        equal(5, Args.parse(new String[] {"next"}).intOption("count", 5), "the fallback");
    }

    @Test("a non-numeric integer option is refused by name")
    static void intOptionRejectsRubbish() {
        Args args = Args.parse(new String[] {"next", "--count", "lots"});

        IllegalArgumentException error =
                throws_(IllegalArgumentException.class, () -> args.intOption("count", 5));
        contains(error.getMessage(), "--count", "the message names the option");
        contains(error.getMessage(), "lots", "the message quotes the value");
    }

    @Test("unknown options are reported, sorted, and known ones are not")
    static void unknownOptions() {
        Args args = Args.parse(
                new String[] {"next", "--zebra", "--count", "3", "--apple", "--from", "x"});

        equal(List.of("apple", "zebra"), args.unknownIn("count", "from"), "the unknown options");
        isTrue(args.unknownIn("count", "from", "apple", "zebra").isEmpty(),
                "nothing unknown when all are accepted");
    }

    // ---- help and version -------------------------------------------------

    @Test("help lists every command")
    static void helpListsCommands() {
        Result result = run("help");

        isTrue(result.succeeded(), "help to exit zero");
        for (String command : new String[] {"next", "validate", "replay", "demo", "version"}) {
            contains(result.out(), command, "the help text to mention " + command);
        }
    }

    @Test("no arguments prints the help rather than failing")
    static void noArgumentsPrintsHelp() {
        Result result = run();

        isTrue(result.succeeded(), "an empty command line to exit zero");
        contains(result.out(), "Usage:", "the usage text");
    }

    @Test("version prints the name and number")
    static void versionPrints() {
        Result result = run("version");

        isTrue(result.succeeded(), "version to exit zero");
        contains(result.out(), "cadence", "the name");
        contains(result.out(), "0.1.0", "the version");
    }

    @Test("an unknown command exits with a usage code and points at help")
    static void unknownCommand() {
        Result result = run("frobnicate");

        equal(Main.EXIT_USAGE, result.code(), "the exit code");
        contains(result.err(), "frobnicate", "the message quotes the command");
        contains(result.err(), "help", "the message points at help");
    }

    // ---- next -------------------------------------------------------------

    @Test("next prints the requested number of fire times")
    static void nextPrintsFires() {
        Result result = run("next", "0 9 * * *", "--count", "3", "--from", "2026-10-28T00:00");

        isTrue(result.succeeded(), "next to exit zero");
        contains(result.out(), "2026-10-28T09:00", "the first fire");
        contains(result.out(), "2026-10-29T09:00", "the second fire");
        contains(result.out(), "2026-10-30T09:00", "the third fire");
        isFalse(result.out().contains("2026-10-31T09:00"), "a fourth fire beyond the count");
    }

    @Test("next accepts a bare date as midnight")
    static void nextAcceptsABareDate() {
        // The form people actually type.
        Result result = run("next", "@daily", "--count", "1", "--from", "2026-10-28");

        isTrue(result.succeeded(), "next to exit zero");
        contains(result.out(), "2026-10-29T00:00", "the fire after midnight on the 28th");
    }

    @Test("next without an expression explains what it needs")
    static void nextNeedsAnExpression() {
        Result result = run("next");

        equal(Main.EXIT_USAGE, result.code(), "the exit code");
        contains(result.err(), "needs a cron expression", "the message");
        contains(result.err(), "cadence next", "an example");
    }

    @Test("next refuses a bad expression with the parser's own message")
    static void nextRefusesABadExpression() {
        Result result = run("next", "0 0 * * SMARCH");

        equal(Main.EXIT_USAGE, result.code(), "the exit code");
        contains(result.err(), "SMARCH", "the offending token");
        contains(result.err(), "day-of-week", "the field at fault");
    }

    @Test("next refuses a count below one")
    static void nextRefusesABadCount() {
        equal(Main.EXIT_USAGE, run("next", "@daily", "--count", "0").code(), "the exit code");
        equal(Main.EXIT_USAGE, run("next", "@daily", "--count", "nope").code(), "a non-numeric");
    }

    @Test("next refuses an unparseable from")
    static void nextRefusesABadFrom() {
        Result result = run("next", "@daily", "--from", "yesterday");

        equal(Main.EXIT_USAGE, result.code(), "the exit code");
        contains(result.err(), "ISO-8601", "the message names the accepted form");
    }

    @Test("next reports a schedule that will never fire rather than printing nothing")
    static void nextReportsAnImpossibleSchedule() {
        Result result = run("next", "0 0 30 2 *", "--from", "2026-01-01T00:00");

        contains(result.out(), "no further fires", "the message");
    }

    @Test("next reports an unknown option instead of ignoring it")
    static void nextReportsUnknownOptions() {
        Result result = run("next", "@daily", "--conut", "3");

        equal(Main.EXIT_USAGE, result.code(), "the exit code");
        contains(result.err(), "--conut", "the message quotes the typo");
        contains(result.err(), "--count", "the message lists what is accepted");
    }

    // ---- validate ---------------------------------------------------------

    @Test("validate prints each parsed field")
    static void validatePrintsFields() {
        Result result = run("validate", "*/15 9-17 * * MON-FRI");

        isTrue(result.succeeded(), "validate to exit zero");
        contains(result.out(), "is valid", "the verdict");
        contains(result.out(), "minute", "the minute field");
        contains(result.out(), "day-of-week", "the day-of-week field");
        contains(result.out(), "next fire", "the next fire");
    }

    @Test("validate warns when both day fields are restricted")
    static void validateWarnsAboutTheUnionRule() {
        // The rule surprises everyone who has not met it, and the moment
        // someone is checking a schedule is the moment to mention it.
        Result result = run("validate", "0 0 1 * MON");

        contains(result.out(), "EITHER", "the warning");
    }

    @Test("validate does not warn when only one day field is restricted")
    static void validateIsQuietWhenItShouldBe() {
        isFalse(run("validate", "0 0 1 * *").out().contains("EITHER"),
                "a warning about a single restricted day field");
        isFalse(run("validate", "0 0 * * MON").out().contains("EITHER"),
                "a warning about a single restricted weekday field");
    }

    @Test("validate refuses a bad expression and needs one at all")
    static void validateRefusesBadInput() {
        equal(Main.EXIT_USAGE, run("validate").code(), "no expression");
        equal(Main.EXIT_USAGE, run("validate", "* * *").code(), "too few fields");
    }

    // ---- replay -----------------------------------------------------------

    @Test("replay prints a journal's records")
    static void replayPrintsRecords() {
        withTempDirectory(directory -> {
            Path path = directory.resolve("cadence.journal");
            JobId id = JobId.generate();
            try (Journal journal = Journal.open(path)) {
                journal.append(JournalEntry.of(JournalEntry.Kind.SUBMITTED, id, 1_793_000_000_000L));
                journal.append(JournalEntry.of(JournalEntry.Kind.STARTED, id, 1_793_000_001_000L));
                journal.append(
                        JournalEntry.of(JournalEntry.Kind.SUCCEEDED, id, 1_793_000_002_000L));
            }

            Result result = run("replay", path.toString());

            isTrue(result.succeeded(), "replay to exit zero");
            contains(result.out(), "replayed 3 records", "the summary");
            contains(result.out(), "submitted", "the first record");
            contains(result.out(), "succeeded", "the last record");
            contains(result.out(), id.shortForm(), "the job's short id");
        });
    }

    @Test("replay accepts the journal as an option as well as a positional")
    static void replayAcceptsAnOption() {
        withTempDirectory(directory -> {
            Path path = directory.resolve("cadence.journal");
            try (Journal journal = Journal.open(path)) {
                journal.append(
                        JournalEntry.of(JournalEntry.Kind.SUBMITTED, JobId.generate(), 1L));
            }

            isTrue(run("replay", "--journal", path.toString()).succeeded(),
                    "replay with --journal to exit zero");
        });
    }

    @Test("replay without a file explains what it needs")
    static void replayNeedsAFile() {
        Result result = run("replay");

        equal(Main.EXIT_USAGE, result.code(), "the exit code");
        contains(result.err(), "needs a journal file", "the message");
    }

    @Test("replay of a missing journal reports nothing rather than failing")
    static void replayOfAMissingJournal() {
        withTempDirectory(directory -> {
            Result result = run("replay", directory.resolve("absent.journal").toString());

            isTrue(result.succeeded(), "replay of a missing journal to exit zero");
            contains(result.out(), "replayed 0 records", "the summary");
        });
    }

    @Test("replay of a damaged journal exits non-zero and says where")
    static void replayOfADamagedJournal() {
        withTempDirectory(directory -> {
            Path path = directory.resolve("cadence.journal");
            try (Journal journal = Journal.open(path)) {
                for (int i = 0; i < 4; i++) {
                    journal.append(
                            JournalEntry.of(JournalEntry.Kind.SUBMITTED, JobId.generate(), i));
                }
            }

            List<String> lines = new java.util.ArrayList<>(Files.readAllLines(path));
            lines.set(2, lines.get(2).replace("submitted", "cancelled"));
            Files.write(path, lines, StandardCharsets.UTF_8);

            Result result = run("replay", path.toString());

            equal(1, result.code(), "the exit code");
            contains(result.err(), "checksum", "the damage description");
        });
    }

    @Test("replay of a file that is not a journal fails with an explanation")
    static void replayOfAForeignFile() {
        withTempDirectory(directory -> {
            Path path = directory.resolve("notes.txt");
            Files.writeString(path, "shopping list\nmilk\n", StandardCharsets.UTF_8);

            Result result = run("replay", path.toString());

            equal(1, result.code(), "the exit code");
            contains(result.err(), "does not look like a cadence journal", "the message");
        });
    }

    // ---- demo -------------------------------------------------------------

    @Test("the demo runs jobs, retries one, and reports counters")
    static void demoRuns() {
        // The one test that exercises the real loop against the real clock,
        // which is how the missing wake-on-completion was found: the flaky
        // handler only succeeds on its second attempt, so this fails if a
        // retry is slept through.
        Result result = run("demo", "--seconds", "2");

        isTrue(result.succeeded(), "the demo to exit zero, stderr was: " + result.err());
        contains(result.out(), "greet ran", "the one-shot job");
        contains(result.out(), "flaky succeeded on attempt 2", "the retried job");
        contains(result.out(), "jobs.submitted", "the counters");
        contains(result.out(), "jobs.retried", "the retry counter");
    }

    @Test("the demo writes a journal that replays")
    static void demoWritesAReplayableJournal() {
        withTempDirectory(directory -> {
            Path path = directory.resolve("demo.journal");

            isTrue(run("demo", "--seconds", "2", "--journal", path.toString()).succeeded(),
                    "the demo to exit zero");

            Result replayed = run("replay", path.toString());
            isTrue(replayed.succeeded(), "the replay to exit zero");
            contains(replayed.out(), "submitted", "a submitted record");
            contains(replayed.out(), "succeeded", "a succeeded record");
        });
    }

    @Test("the demo refuses an unreasonable duration")
    static void demoRefusesABadDuration() {
        equal(Main.EXIT_USAGE, run("demo", "--seconds", "0").code(), "zero seconds");
        equal(Main.EXIT_USAGE, run("demo", "--seconds", "600").code(), "ten minutes");
    }

    // ---- helpers ----------------------------------------------------------

    @FunctionalInterface
    private interface DirectoryBody {
        void run(Path directory) throws Exception;
    }

    /** Runs {@code body} against a fresh temporary directory, then removes it. */
    private static void withTempDirectory(DirectoryBody body) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("cadence-cli-test");
            body.run(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (directory != null) {
                try (var walk = Files.walk(directory)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // A leftover temp file is not worth a failure.
                        }
                    });
                } catch (IOException ignored) {
                    // Likewise.
                }
            }
        }
    }
}
