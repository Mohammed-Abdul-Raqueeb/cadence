package dev.raqueeb.cadence.cron;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import java.time.LocalDateTime;
import java.util.Optional;

/** Parsing whole expressions, and computing when they next fire. */
final class CronExpressionTest {

    private static LocalDateTime at(String iso) {
        return LocalDateTime.parse(iso);
    }

    /** The next fire after {@code from}, as an ISO string, for readable assertions. */
    private static String next(String expression, String from) {
        return CronExpression.parse(expression)
                .nextAfter(at(from))
                .map(LocalDateTime::toString)
                .orElse("never");
    }

    // ---- shape ------------------------------------------------------------

    @Test("five fields parse")
    static void fiveFieldsParse() {
        CronExpression cron = CronExpression.parse("30 9 1 1 *");

        equal("30 9 1 1 *", cron.expression(), "the round-tripped expression");
        equal(30, cron.minuteField().first(), "the minute");
        equal(9, cron.hourField().first(), "the hour");
    }

    @Test("runs of whitespace separate fields")
    static void extraWhitespaceIsAllowed() {
        // Expressions get pasted out of aligned crontabs, where the columns
        // are padded with spaces.
        CronExpression cron = CronExpression.parse("  0   12  *  *   1-5  ");

        equal("0   12  *  *   1-5", cron.expression(), "the trimmed expression");
        isTrue(cron.matches(at("2026-10-28T12:00")), "noon on a Wednesday to match");
    }

    @Test("the wrong number of fields is rejected, with the count")
    static void wrongFieldCountIsRejected() {
        CronParseException tooFew =
                throws_(CronParseException.class, () -> CronExpression.parse("* * *"));
        contains(tooFew.getMessage(), "has 3 fields", "the message counts what was given");
        contains(tooFew.getMessage(), "day-of-week", "the message names the fields expected");

        throws_(CronParseException.class, () -> CronExpression.parse("* * * * * *"));
    }

    @Test("null and empty expressions are rejected")
    static void nullAndEmptyAreRejected() {
        throws_(CronParseException.class, () -> CronExpression.parse(null));
        throws_(CronParseException.class, () -> CronExpression.parse(""));
        throws_(CronParseException.class, () -> CronExpression.parse("   "));
    }

    // ---- macros -----------------------------------------------------------

    @Test("macros expand to their expressions")
    static void macrosExpand() {
        equal(CronExpression.parse("0 * * * *"), CronExpression.parse("@hourly"), "@hourly");
        equal(CronExpression.parse("0 0 * * *"), CronExpression.parse("@daily"), "@daily");
        equal(CronExpression.parse("0 0 * * *"), CronExpression.parse("@midnight"), "@midnight");
        equal(CronExpression.parse("0 0 * * 0"), CronExpression.parse("@weekly"), "@weekly");
        equal(CronExpression.parse("0 0 1 * *"), CronExpression.parse("@monthly"), "@monthly");
        equal(CronExpression.parse("0 0 1 1 *"), CronExpression.parse("@yearly"), "@yearly");
        equal(CronExpression.parse("@yearly"), CronExpression.parse("@annually"), "@annually");
    }

    @Test("a macro keeps its own text for round-tripping")
    static void macroKeepsItsText() {
        // The journal stores what the user wrote, so an expression read back
        // still reads as @daily rather than as its expansion.
        equal("@daily", CronExpression.parse("@daily").expression(), "the stored text");
    }

    @Test("macros are case-insensitive")
    static void macrosAreCaseInsensitive() {
        equal(CronExpression.parse("@daily"), CronExpression.parse("@DAILY"), "@DAILY");
    }

    @Test("an unknown macro lists the known ones")
    static void unknownMacroIsRejected() {
        CronParseException error =
                throws_(CronParseException.class, () -> CronExpression.parse("@fortnightly"));

        contains(error.getMessage(), "@fortnightly", "the message quotes what was written");
        contains(error.getMessage(), "@daily", "the message lists a real macro");
    }

    // ---- next fire, within a day -----------------------------------------

    @Test("the next fire is strictly after the instant given")
    static void nextIsStrictlyAfter() {
        // The scheduler reschedules from the time a job just fired. Were this
        // inclusive, a job would reschedule onto the instant it had just run
        // and spin.
        equal("2026-10-29T00:00", next("@daily", "2026-10-28T00:00"), "from exactly midnight");
    }

    @Test("seconds on the input do not delay the next fire")
    static void secondsAreTruncated() {
        // 00:00:30 is still within the 00:00 minute, so the next daily fire
        // is tomorrow, not today.
        equal("2026-10-29T00:00", next("@daily", "2026-10-28T00:00:30"), "from half past midnight");
    }

    @Test("an every-minute schedule advances one minute")
    static void everyMinute() {
        equal("2026-10-28T09:16", next("* * * * *", "2026-10-28T09:15:45"), "the next minute");
    }

    @Test("a minute list finds the next entry in the hour")
    static void minuteListWithinTheHour() {
        equal("2026-10-28T09:15", next("0,15,30,45 * * * *", "2026-10-28T09:01"), "quarter past");
        equal("2026-10-28T09:30", next("0,15,30,45 * * * *", "2026-10-28T09:15"), "half past");
        equal("2026-10-28T10:00", next("0,15,30,45 * * * *", "2026-10-28T09:45"), "the next hour");
    }

    @Test("an hour window rolls to the next day once it has passed")
    static void hourWindowRollsToTomorrow() {
        equal("2026-10-28T09:00", next("0 9-17 * * *", "2026-10-28T08:30"), "the window opening");
        equal("2026-10-28T17:00", next("0 9-17 * * *", "2026-10-28T16:30"), "the window closing");
        equal("2026-10-29T09:00", next("0 9-17 * * *", "2026-10-28T17:30"), "tomorrow's opening");
    }

    @Test("the minute floor applies only to the hour the search starts in")
    static void minuteFloorOnlyAppliesToTheFirstHour() {
        // Starting at 09:40 with minute 30, the 09:30 fire has passed, so the
        // answer is 10:30 -- not 10:40 and not nothing. Carrying the minute
        // floor into later hours is the obvious way to get this wrong.
        equal("2026-10-28T10:30", next("30 * * * *", "2026-10-28T09:40"), "the next half past");
    }

    // ---- next fire, across boundaries ------------------------------------

    @Test("a fire crosses midnight")
    static void crossesMidnight() {
        equal("2026-10-29T00:00", next("0 0 * * *", "2026-10-28T23:59"), "just before midnight");
    }

    @Test("a fire crosses a month boundary")
    static void crossesMonthBoundary() {
        equal("2026-11-01T00:00", next("0 0 * * *", "2026-10-31T12:00"), "the first of November");
    }

    @Test("a fire crosses a year boundary")
    static void crossesYearBoundary() {
        equal("2027-01-01T00:00", next("0 0 * * *", "2026-12-31T23:59"), "new year");
    }

    @Test("a monthly schedule skips to the first of the next month")
    static void monthlySkipsAhead() {
        equal("2026-11-01T00:00", next("@monthly", "2026-10-02T00:00"), "the first of November");
    }

    @Test("a yearly schedule skips a whole year")
    static void yearlySkipsAhead() {
        equal("2027-01-01T00:00", next("@yearly", "2026-01-01T00:01"), "next new year");
    }

    @Test("a single-month schedule skips the intervening months")
    static void singleMonthSkipsAhead() {
        equal("2027-02-01T00:00", next("0 0 1 2 *", "2026-03-01T00:00"), "next February");
    }

    @Test("a day-of-month beyond a short month falls to a month that has it")
    static void shortMonthsAreSkipped() {
        // The 31st does not exist in November, so a schedule for the 31st
        // skips to December rather than firing on the 30th or on 1 December.
        equal("2026-12-31T00:00", next("0 0 31 * *", "2026-11-01T00:00"), "the next 31st");
    }

    @Test("29 February is found across the leap gap")
    static void leapDayIsFound() {
        // 2028 is the next leap year after 2026. This is the case the eight
        // year search horizon exists for.
        equal("2028-02-29T00:00", next("0 0 29 2 *", "2026-03-01T00:00"), "the next leap day");
    }

    @Test("a schedule that cannot fire reports never rather than hanging")
    static void impossibleScheduleReportsNever() {
        // 30 February is a legal expression and an impossible date. The search
        // has to terminate and say so, not spin to the end of time.
        equal("never", next("0 0 30 2 *", "2026-01-01T00:00"), "30 February");
    }

    // ---- the day-of-month and day-of-week rule ---------------------------

    @Test("a weekday schedule fires only on those weekdays")
    static void weekdaysOnly() {
        // 2026-10-30 is a Friday, so the next working-day fire is Monday the
        // 2nd of November.
        equal("2026-11-02T09:00", next("0 9 * * MON-FRI", "2026-10-30T09:30"), "Monday morning");
    }

    @Test("day-of-month and day-of-week are unioned when both are restricted")
    static void bothDayFieldsAreUnioned() {
        // Cron's one genuinely surprising rule, and not a bug reproduced for
        // compatibility: `1 * MON` means the first of the month OR any
        // Monday. Intersecting them would match almost nothing.
        CronExpression cron = CronExpression.parse("0 0 1 * MON");

        // 2026-11-01 is a Sunday -- matched by the day-of-month half.
        isTrue(cron.matches(at("2026-11-01T00:00")), "the first of the month");
        // 2026-11-02 is a Monday -- matched by the day-of-week half.
        isTrue(cron.matches(at("2026-11-02T00:00")), "a Monday");
        // 2026-11-03 is a Tuesday and not the first.
        isFalse(cron.matches(at("2026-11-03T00:00")), "a Tuesday that is not the first");
    }

    @Test("a restricted day-of-month alone is not unioned with every weekday")
    static void monthDayAloneIsNotUnioned() {
        CronExpression cron = CronExpression.parse("0 0 15 * *");

        isTrue(cron.matches(at("2026-10-15T00:00")), "the fifteenth");
        isFalse(cron.matches(at("2026-10-16T00:00")), "the sixteenth");
    }

    @Test("a full range in a day field still counts as restricted")
    static void aFullRangeIsStillRestricted() {
        // `0-6` matches every weekday, so unioning it with day-of-month makes
        // every day match. That is the documented consequence of writing a
        // range instead of a star, and it is why the two are distinguished.
        CronExpression starred = CronExpression.parse("0 0 15 * *");
        CronExpression ranged = CronExpression.parse("0 0 15 * 0-6");

        isFalse(starred.matches(at("2026-10-16T00:00")), "the sixteenth with a starred weekday");
        isTrue(ranged.matches(at("2026-10-16T00:00")), "the sixteenth with a ranged weekday");
    }

    @Test("Sunday is matched by both 0 and 7")
    static void sundayBothWays() {
        // 2026-11-01 is a Sunday.
        isTrue(CronExpression.parse("0 0 * * 0").matches(at("2026-11-01T00:00")), "day 0");
        isTrue(CronExpression.parse("0 0 * * 7").matches(at("2026-11-01T00:00")), "day 7");
    }

    @Test("the weekday mapping is not off by one")
    static void weekdayMappingIsCorrect() {
        // java.time counts Monday as 1 and Sunday as 7; cron counts Sunday as
        // 0. An off-by-one here shifts every weekly schedule by a day, which
        // is the sort of bug that is only noticed in production.
        isTrue(CronExpression.parse("0 0 * * SUN").matches(at("2026-11-01T00:00")), "Sunday");
        isTrue(CronExpression.parse("0 0 * * MON").matches(at("2026-11-02T00:00")), "Monday");
        isTrue(CronExpression.parse("0 0 * * TUE").matches(at("2026-11-03T00:00")), "Tuesday");
        isTrue(CronExpression.parse("0 0 * * WED").matches(at("2026-11-04T00:00")), "Wednesday");
        isTrue(CronExpression.parse("0 0 * * THU").matches(at("2026-11-05T00:00")), "Thursday");
        isTrue(CronExpression.parse("0 0 * * FRI").matches(at("2026-11-06T00:00")), "Friday");
        isTrue(CronExpression.parse("0 0 * * SAT").matches(at("2026-11-07T00:00")), "Saturday");
    }

    // ---- matches ----------------------------------------------------------

    @Test("matches ignores seconds, having no field for them")
    static void matchesIgnoresSeconds() {
        CronExpression cron = CronExpression.parse("0 12 * * *");

        isTrue(cron.matches(at("2026-10-28T12:00:00")), "noon exactly");
        isTrue(cron.matches(at("2026-10-28T12:00:59")), "noon and fifty-nine seconds");
        isFalse(cron.matches(at("2026-10-28T12:01:00")), "a minute past noon");
    }

    @Test("every minute a schedule reports matching is one it would next fire at")
    static void matchesAgreesWithNextAfter() {
        // The two are computed by different code, so walking a day of minutes
        // and demanding they agree catches a drift between them that neither
        // one's own tests would.
        CronExpression cron = CronExpression.parse("*/7 9-17 * * *");
        LocalDateTime cursor = at("2026-10-28T00:00");

        for (int fires = 0; fires < 50; fires++) {
            Optional<LocalDateTime> fire = cron.nextAfter(cursor);
            isTrue(fire.isPresent(), "a fire within the day");
            isTrue(cron.matches(fire.get()), "matches() to agree about " + fire.get());
            cursor = fire.get();
        }
    }

    @Test("stepping through next fires never goes backwards or repeats")
    static void nextFiresAdvanceMonotonically() {
        CronExpression cron = CronExpression.parse("0 0 1 * MON");
        LocalDateTime cursor = at("2026-01-01T00:00");

        for (int fires = 0; fires < 200; fires++) {
            LocalDateTime fire = cron.nextAfter(cursor).orElseThrow();
            isTrue(fire.isAfter(cursor), "each fire to follow the last: " + cursor + " -> " + fire);
            cursor = fire;
        }
    }

    // ---- value semantics --------------------------------------------------

    @Test("equality is by the parsed fields, not the text")
    static void equalityIgnoresSpelling() {
        equal(CronExpression.parse("0 0 * * 0"), CronExpression.parse("0 0 * * SUN"),
                "two spellings of the same schedule");
        equal(CronExpression.parse("0 0 * * 0").hashCode(),
                CronExpression.parse("0 0 * * 7").hashCode(),
                "the hash of two spellings of Sunday");
    }

    @Test("different schedules are not equal")
    static void differentSchedulesDiffer() {
        isFalse(CronExpression.parse("0 0 * * *").equals(CronExpression.parse("0 1 * * *")),
                "midnight to equal one in the morning");
    }

    @Test("toString returns the expression as written")
    static void toStringIsTheExpression() {
        equal("*/5 * * * *", CronExpression.parse("*/5 * * * *").toString(), "the text");
    }
}
