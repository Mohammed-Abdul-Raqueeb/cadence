package dev.raqueeb.cadence.cli;

import dev.raqueeb.cadence.Cadence;
import dev.raqueeb.cadence.cron.CronExpression;
import dev.raqueeb.cadence.cron.CronParseException;
import dev.raqueeb.cadence.exec.Clock;
import dev.raqueeb.cadence.exec.Scheduler;
import dev.raqueeb.cadence.exec.WorkerPool;
import dev.raqueeb.cadence.job.JobSpec;
import dev.raqueeb.cadence.store.Journal;
import dev.raqueeb.cadence.store.JournalEntry;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * The {@code cadence} command.
 *
 * <p>Every command is a method taking parsed {@link Args} and the streams to
 * write to, returning an exit code. Nothing calls {@link System#exit} except
 * {@link #main}, which is what makes each one testable without a subprocess.
 */
public final class Main {

    /** Exit code for a usage error, as distinct from a failed operation. */
    static final int EXIT_USAGE = 2;

    private Main() {
    }

    /**
     * Runs the command line.
     *
     * @param argv the arguments
     */
    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /**
     * Runs a command against the streams given.
     *
     * @param argv the arguments
     * @param out  where output goes
     * @param err  where errors go
     * @return the exit code: 0 for success, 1 for a failure, 2 for misuse
     */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        Args args = Args.parse(argv);

        try {
            return switch (args.command()) {
                case "", "help", "--help" -> usage(out);
                case "version", "--version" -> version(out);
                case "next" -> next(args, out, err);
                case "validate" -> validate(args, out, err);
                case "replay" -> replay(args, out, err);
                case "demo" -> demo(args, out, err);
                default -> {
                    err.println("cadence: unknown command \"" + args.command() + "\"");
                    err.println("Run \"cadence help\" for the list.");
                    yield EXIT_USAGE;
                }
            };
        } catch (IllegalArgumentException e) {
            // Every deliberate refusal in this project is an
            // IllegalArgumentException with a message written for a person, so
            // printing it is better than a stack trace.
            err.println("cadence: " + e.getMessage());
            return EXIT_USAGE;
        } catch (RuntimeException e) {
            err.println("cadence: " + e);
            return 1;
        }
    }

    /** Prints the usage text. */
    private static int usage(PrintStream out) {
        out.println(Cadence.identity() + " - a job scheduler built on the JDK alone");
        out.println();
        out.println("Usage: cadence <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  next <cron>        when a schedule fires next");
        out.println("  validate <cron>    check a schedule and describe it");
        out.println("  replay <journal>   print a journal's history");
        out.println("  demo               run a short scheduler demonstration");
        out.println("  version            print the version");
        out.println("  help               print this text");
        out.println();
        out.println("Options:");
        out.println("  --count <n>        how many fire times to print (next, default 5)");
        out.println("  --from <when>      the instant to search after, ISO-8601 (next)");
        out.println("  --journal <path>   a journal file to write or read");
        out.println("  --seconds <n>      how long to run (demo, default 3)");
        out.println();
        out.println("Examples:");
        out.println("  cadence next \"*/15 9-17 * * MON-FRI\" --count 8");
        out.println("  cadence validate @daily");
        out.println("  cadence replay ./cadence.journal");
        return 0;
    }

    private static int version(PrintStream out) {
        out.println(Cadence.identity());
        return 0;
    }

    /** Prints the next few fire times of a schedule. */
    private static int next(Args args, PrintStream out, PrintStream err) {
        if (reportUnknown(args, err, "count", "from")) {
            return EXIT_USAGE;
        }

        Optional<String> expression = args.positional(0);
        if (expression.isEmpty()) {
            err.println("cadence: next needs a cron expression");
            err.println("  cadence next \"0 9 * * MON-FRI\"");
            return EXIT_USAGE;
        }

        int count = args.intOption("count", 5);
        if (count < 1) {
            err.println("cadence: --count must be at least 1, got " + count);
            return EXIT_USAGE;
        }

        CronExpression cron;
        try {
            cron = CronExpression.parse(expression.orElseThrow());
        } catch (CronParseException e) {
            err.println("cadence: " + e.getMessage());
            return EXIT_USAGE;
        }

        LocalDateTime from = args.option("from")
                .map(Main::parseWhen)
                .orElseGet(LocalDateTime::now);

        out.println(cron.expression() + "  from " + from);
        LocalDateTime cursor = from;
        for (int i = 0; i < count; i++) {
            Optional<LocalDateTime> fire = cron.nextAfter(cursor);
            if (fire.isEmpty()) {
                out.println("  (no further fires within eight years)");
                break;
            }
            out.printf("  %-20s %s%n", fire.orElseThrow(), describeGap(cursor, fire.orElseThrow()));
            cursor = fire.orElseThrow();
        }
        return 0;
    }

    /** Checks a schedule and describes what it matches. */
    private static int validate(Args args, PrintStream out, PrintStream err) {
        if (reportUnknown(args, err)) {
            return EXIT_USAGE;
        }

        Optional<String> expression = args.positional(0);
        if (expression.isEmpty()) {
            err.println("cadence: validate needs a cron expression");
            return EXIT_USAGE;
        }

        CronExpression cron;
        try {
            cron = CronExpression.parse(expression.orElseThrow());
        } catch (CronParseException e) {
            err.println("cadence: " + e.getMessage());
            return EXIT_USAGE;
        }

        out.println(cron.expression() + "  is valid");
        out.println("  minute        " + cron.minuteField());
        out.println("  hour          " + cron.hourField());
        out.println("  day-of-month  " + cron.dayOfMonthField());
        out.println("  month         " + cron.monthField());
        out.println("  day-of-week   " + cron.dayOfWeekField());

        boolean bothDays = !cron.dayOfMonthField().isWildcard()
                && !cron.dayOfWeekField().isWildcard();
        if (bothDays) {
            // Worth saying out loud, because it surprises people who have not
            // met it: the two day fields are unioned, not intersected.
            out.println();
            out.println("  Note: day-of-month and day-of-week are both restricted, so this");
            out.println("  fires on days matching EITHER of them, not only days matching both.");
        }

        Optional<LocalDateTime> fire = cron.nextAfter(LocalDateTime.now());
        out.println();
        out.println("  next fire     " + fire.map(Object::toString)
                .orElse("never, within eight years"));
        return fire.isPresent() ? 0 : 1;
    }

    /** Prints a journal's history. */
    private static int replay(Args args, PrintStream out, PrintStream err) {
        if (reportUnknown(args, err, "journal")) {
            return EXIT_USAGE;
        }

        Optional<String> file = args.positional(0).or(() -> args.option("journal"));
        if (file.isEmpty()) {
            err.println("cadence: replay needs a journal file");
            err.println("  cadence replay ./cadence.journal");
            return EXIT_USAGE;
        }

        Path path = Path.of(file.orElseThrow());
        Journal.Replay result = Journal.replay(path);

        out.println(path + "  " + result.summary());
        out.println();
        for (JournalEntry entry : result.entries()) {
            LocalDateTime when = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(entry.atMillis()), ZoneId.systemDefault());
            out.printf("  %s  %-12s %s%n", when, entry.kind().wireName(),
                    entry.jobId().shortForm());
        }

        if (result.isDamaged()) {
            err.println();
            err.println("cadence: " + result.damage());
            return 1;
        }
        return 0;
    }

    /** Runs a short demonstration so the thing can be seen working. */
    private static int demo(Args args, PrintStream out, PrintStream err) {
        if (reportUnknown(args, err, "seconds", "journal")) {
            return EXIT_USAGE;
        }

        int seconds = args.intOption("seconds", 3);
        if (seconds < 1 || seconds > 60) {
            err.println("cadence: --seconds must be between 1 and 60, got " + seconds);
            return EXIT_USAGE;
        }

        Journal journal = args.option("journal").map(file -> Journal.open(Path.of(file)))
                .orElse(null);

        // A one-thread pool and a real clock: the point of the demo is that
        // the ordinary path works, so nothing here is stubbed.
        try (WorkerPool pool = new WorkerPool(2, 16, "cadence-demo");
                Scheduler scheduler = Scheduler.builder()
                        .clock(Clock.system())
                        .workers(pool)
                        .journal(journal)
                        .log(err::println)
                        .build()) {

            scheduler.register("greet", context ->
                    out.println("    greet ran, attempt " + context.attempt()
                            + ", payload \"" + context.payload() + "\""));
            scheduler.register("flaky", context -> {
                if (context.attempt() < 2) {
                    throw new IllegalStateException("failing on purpose the first time");
                }
                out.println("    flaky succeeded on attempt " + context.attempt());
            });

            out.println("Running for " + seconds + "s with handlers "
                    + scheduler.handlerNames());

            scheduler.submit(JobSpec.forHandler("greet").payload("hello").build());
            scheduler.submit(JobSpec.forHandler("flaky")
                    .retryPolicy(dev.raqueeb.cadence.job.RetryPolicy.builder()
                            .maxAttempts(3)
                            .baseDelay(Duration.ofMillis(200))
                            .maxDelay(Duration.ofSeconds(1))
                            .build())
                    .build());
            scheduler.submit(JobSpec.forHandler("greet")
                    .name("every minute")
                    .schedule("* * * * *")
                    .payload("recurring")
                    .build());

            Thread loop = scheduler.start();
            try {
                Thread.sleep(Duration.ofSeconds(seconds).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler.stop();
            try {
                loop.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            out.println();
            out.println("Counters:");
            scheduler.metrics().counterValues()
                    .forEach((name, value) -> out.printf("  %-18s %d%n", name, value));
            out.println("Durations: " + scheduler.metrics().histogram("jobs.duration.ms"));
            out.println("Jobs:");
            scheduler.jobs().forEach(job -> out.println("  " + job));
        }
        return 0;
    }

    /** Prints a complaint about unrecognised options, and whether there were any. */
    private static boolean reportUnknown(Args args, PrintStream err, String... known) {
        List<String> unknown = args.unknownIn(known);
        if (unknown.isEmpty()) {
            return false;
        }
        for (String name : unknown) {
            err.println("cadence: unknown option \"--" + name + "\"");
        }
        if (known.length > 0) {
            err.println("This command accepts: --" + String.join(", --", known));
        }
        return true;
    }

    /** Parses a date, a date and time, or reports what the accepted forms are. */
    private static LocalDateTime parseWhen(String text) {
        try {
            return LocalDateTime.parse(text);
        } catch (java.time.format.DateTimeParseException ignored) {
            // A bare date is the form people type, so it is accepted and
            // taken as midnight rather than refused.
            try {
                return java.time.LocalDate.parse(text).atStartOfDay();
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("--from needs an ISO-8601 date or date-time,"
                        + " such as 2026-10-28 or 2026-10-28T09:30, got \"" + text + "\"");
            }
        }
    }

    /** Describes the gap between two fires, for the right-hand column. */
    private static String describeGap(LocalDateTime from, LocalDateTime to) {
        Duration gap = Duration.between(from, to);
        if (gap.toDays() > 0) {
            return "(+" + gap.toDays() + "d " + gap.toHoursPart() + "h)";
        }
        if (gap.toHours() > 0) {
            return "(+" + gap.toHours() + "h " + gap.toMinutesPart() + "m)";
        }
        return "(+" + gap.toMinutes() + "m)";
    }
}
