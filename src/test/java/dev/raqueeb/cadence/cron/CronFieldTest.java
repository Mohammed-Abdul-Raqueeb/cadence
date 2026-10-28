package dev.raqueeb.cadence.cron;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isFalse;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import java.util.List;

/** Parsing one field of a cron expression. */
final class CronFieldTest {

    /** Parses a field, using the text itself as the expression in errors. */
    private static CronField field(FieldType type, String text) {
        return CronField.parse(type, text, text);
    }

    private static List<Integer> values(FieldType type, String text) {
        return field(type, text).values();
    }

    // ---- single values ----------------------------------------------------

    @Test("a single number matches only itself")
    static void singleNumber() {
        CronField minute = field(FieldType.MINUTE, "30");

        equal(List.of(30), minute.values(), "the matching values");
        isTrue(minute.matches(30), "30 to match");
        isFalse(minute.matches(29), "29 to match");
        isFalse(minute.matches(31), "31 to match");
    }

    @Test("the lowest and highest legal values parse")
    static void boundsParse() {
        equal(List.of(0), values(FieldType.MINUTE, "0"), "minute 0");
        equal(List.of(59), values(FieldType.MINUTE, "59"), "minute 59");
        equal(List.of(0), values(FieldType.HOUR, "0"), "hour 0");
        equal(List.of(23), values(FieldType.HOUR, "23"), "hour 23");
        equal(List.of(1), values(FieldType.DAY_OF_MONTH, "1"), "day 1");
        equal(List.of(31), values(FieldType.DAY_OF_MONTH, "31"), "day 31");
    }

    // ---- wildcards --------------------------------------------------------

    @Test("a wildcard matches every value in the field")
    static void wildcardMatchesEverything() {
        CronField minute = field(FieldType.MINUTE, "*");

        equal(60, minute.count(), "the number of matching minutes");
        isTrue(minute.isWildcard(), "the field to report itself a wildcard");
        isTrue(minute.matches(0), "minute 0 to match");
        isTrue(minute.matches(59), "minute 59 to match");
    }

    @Test("a full range is not a wildcard, though it matches the same values")
    static void aFullRangeIsNotAWildcard() {
        // The distinction decides whether day-of-month and day-of-week are
        // intersected or unioned, so matching everything is not enough to
        // make a field unrestricted.
        CronField explicit = field(FieldType.DAY_OF_WEEK, "0-6");

        equal(7, explicit.count(), "the number of matching days");
        isFalse(explicit.isWildcard(), "an explicit range to be a wildcard");
    }

    // ---- ranges -----------------------------------------------------------

    @Test("a range matches both ends and everything between")
    static void rangeIsInclusive() {
        equal(List.of(9, 10, 11, 12), values(FieldType.HOUR, "9-12"), "hours 9 through 12");
    }

    @Test("a range of one value is the value")
    static void degenerateRange() {
        equal(List.of(5), values(FieldType.HOUR, "5-5"), "hour 5 through 5");
    }

    @Test("a descending range is rejected rather than wrapped")
    static void descendingRangeIsRejected() {
        // Some cron implementations wrap FRI-MON around the week and some do
        // not. Guessing would make the same expression behave differently
        // here than wherever it was copied from.
        CronParseException error =
                throws_(CronParseException.class, () -> field(FieldType.DAY_OF_WEEK, "5-1"));

        contains(error.getMessage(), "must ascend", "the message explains the rule");
        contains(error.getMessage(), "two entries", "the message suggests the fix");
    }

    // ---- steps ------------------------------------------------------------

    @Test("a step over a wildcard starts at the field minimum")
    static void stepOverWildcard() {
        equal(List.of(0, 15, 30, 45), values(FieldType.MINUTE, "*/15"), "every fifteen minutes");
        equal(List.of(1, 3, 5, 7, 9, 11), values(FieldType.MONTH, "*/2"), "every other month");
    }

    @Test("a step over a range stays inside it")
    static void stepOverRange() {
        equal(List.of(9, 11, 13, 15, 17), values(FieldType.HOUR, "9-17/2"),
                "every other hour of the working day");
    }

    @Test("a step from a single value runs to the end of the field")
    static void stepFromSingleValue() {
        // `10/15` means "from 10 onwards, every 15" rather than "just 10",
        // which is what separates it from a bare `10`.
        equal(List.of(10, 25, 40, 55), values(FieldType.MINUTE, "10/15"), "from minute 10 by 15");
        equal(List.of(10), values(FieldType.MINUTE, "10"), "a bare minute 10");
    }

    @Test("a step wider than the field matches only the first value")
    static void stepWiderThanTheRange() {
        equal(List.of(0), values(FieldType.MINUTE, "*/60"), "a step of the whole field");
    }

    @Test("a step of zero is rejected")
    static void zeroStepIsRejected() {
        CronParseException error =
                throws_(CronParseException.class, () -> field(FieldType.MINUTE, "*/0"));

        contains(error.getMessage(), "at least 1", "the message states the minimum");
    }

    @Test("a step beyond the field width is rejected")
    static void oversizedStepIsRejected() {
        CronParseException error =
                throws_(CronParseException.class, () -> field(FieldType.MINUTE, "*/61"));

        contains(error.getMessage(), "wider than the field", "the message says why");
    }

    @Test("a slash with nothing after it is rejected")
    static void danglingSlashIsRejected() {
        throws_(CronParseException.class, () -> field(FieldType.MINUTE, "*/"));
    }

    // ---- lists ------------------------------------------------------------

    @Test("a list matches each of its entries")
    static void listOfValues() {
        equal(List.of(0, 15, 30, 45), values(FieldType.MINUTE, "0,15,30,45"), "four minutes");
    }

    @Test("a list may mix values, ranges and steps")
    static void mixedList() {
        equal(List.of(0, 1, 2, 30, 40, 50), values(FieldType.MINUTE, "0-2,30,40/10"),
                "a range, a value and a step together");
    }

    @Test("a list collapses duplicates and sorts")
    static void listIsASet() {
        // The field is a set of values, so repeating one is harmless and the
        // order it was written in does not survive.
        equal(List.of(5, 10), values(FieldType.MINUTE, "10,5,10,5"), "the deduplicated values");
    }

    @Test("an empty list entry is rejected")
    static void emptyListEntryIsRejected() {
        CronParseException error =
                throws_(CronParseException.class, () -> field(FieldType.MINUTE, "0,,30"));

        contains(error.getMessage(), "empty entry", "the message names the problem");
    }

    // ---- names ------------------------------------------------------------

    @Test("month names parse, case-insensitively")
    static void monthNames() {
        equal(List.of(1), values(FieldType.MONTH, "JAN"), "January");
        equal(List.of(12), values(FieldType.MONTH, "dec"), "December in lower case");
        equal(List.of(3, 4, 5), values(FieldType.MONTH, "MAR-MAY"), "a named range");
    }

    @Test("weekday names parse")
    static void weekdayNames() {
        equal(List.of(0), values(FieldType.DAY_OF_WEEK, "SUN"), "Sunday");
        equal(List.of(1, 2, 3, 4, 5), values(FieldType.DAY_OF_WEEK, "MON-FRI"), "the working week");
    }

    @Test("an unknown name is rejected by name")
    static void unknownNameIsRejected() {
        CronParseException error =
                throws_(CronParseException.class, () -> field(FieldType.MONTH, "SMARCH"));

        contains(error.getMessage(), "SMARCH", "the message quotes the token");
        contains(error.getMessage(), "not a recognised name", "the message says why");
        contains(error.getMessage(), "JAN", "the message lists what is accepted");
    }

    @Test("a name in a numeric field is rejected")
    static void nameInNumericFieldIsRejected() {
        throws_(CronParseException.class, () -> field(FieldType.MINUTE, "JAN"));
    }

    // ---- day-of-week conventions -----------------------------------------

    @Test("7 and 0 both mean Sunday")
    static void sevenIsSunday() {
        // Both conventions appear in working crontabs. Rejecting one would
        // reject expressions copied from a system that uses it.
        equal(List.of(0), values(FieldType.DAY_OF_WEEK, "7"), "day 7");
        equal(List.of(0), values(FieldType.DAY_OF_WEEK, "0"), "day 0");
        equal(values(FieldType.DAY_OF_WEEK, "0"), values(FieldType.DAY_OF_WEEK, "7"),
                "the two spellings of Sunday");
    }

    @Test("day-of-week 8 is out of range")
    static void eightIsNotADay() {
        throws_(CronParseException.class, () -> field(FieldType.DAY_OF_WEEK, "8"));
    }

    // ---- out of range -----------------------------------------------------

    @Test("a value above the field maximum is rejected")
    static void aboveMaximumIsRejected() {
        CronParseException error =
                throws_(CronParseException.class, () -> field(FieldType.MINUTE, "60"));

        contains(error.getMessage(), "out of range", "the message says out of range");
        contains(error.getMessage(), "0-59", "the message quotes the legal range");
    }

    @Test("day-of-month zero is rejected, since the field is one-based")
    static void dayZeroIsRejected() {
        throws_(CronParseException.class, () -> field(FieldType.DAY_OF_MONTH, "0"));
    }

    @Test("a negative value is rejected, not read as a range")
    static void negativeIsRejected() {
        // The minus is the range separator, so `-5` has no first bound. The
        // search for the dash starts at index 1 so this reports a missing
        // value rather than silently parsing as 0-5.
        throws_(CronParseException.class, () -> field(FieldType.MINUTE, "-5"));
    }

    @Test("a number too large for an int is rejected as a number, not a crash")
    static void hugeNumberIsRejected() {
        CronParseException error = throws_(CronParseException.class,
                () -> field(FieldType.MINUTE, "99999999999999999999"));

        contains(error.getMessage(), "too large", "the message says the number is too large");
    }

    @Test("empty and blank fields are rejected")
    static void emptyFieldIsRejected() {
        throws_(CronParseException.class, () -> field(FieldType.MINUTE, ""));
        throws_(CronParseException.class, () -> field(FieldType.MINUTE, "   "));
    }

    @Test("rubbish is rejected with the token quoted")
    static void rubbishIsRejected() {
        CronParseException error =
                throws_(CronParseException.class, () -> field(FieldType.MINUTE, "every"));

        contains(error.getMessage(), "every", "the message quotes what was written");
    }

    // ---- lookup -----------------------------------------------------------

    @Test("nextAtOrAfter finds the value itself when it matches")
    static void nextAtOrAfterIsInclusive() {
        CronField minute = field(FieldType.MINUTE, "0,30");

        equal(0, minute.nextAtOrAfter(0), "from 0");
        equal(30, minute.nextAtOrAfter(1), "from 1");
        equal(30, minute.nextAtOrAfter(30), "from 30");
    }

    @Test("nextAtOrAfter returns -1 when nothing matches that high")
    static void nextAtOrAfterRunsOut() {
        CronField minute = field(FieldType.MINUTE, "0,30");

        equal(-1, minute.nextAtOrAfter(31), "from 31");
        equal(-1, minute.nextAtOrAfter(100), "from beyond the field");
    }

    @Test("nextAtOrAfter clamps a value below the field minimum")
    static void nextAtOrAfterClampsLow() {
        CronField day = field(FieldType.DAY_OF_MONTH, "1,15");

        // Day-of-month starts at 1, and the search is driven by code that may
        // ask from 0. Clamping is what keeps the shift in range.
        equal(1, day.nextAtOrAfter(0), "from 0 in a one-based field");
        equal(1, day.nextAtOrAfter(-5), "from a negative value");
    }

    @Test("first returns the lowest matching value")
    static void firstIsLowest() {
        equal(5, field(FieldType.MINUTE, "40,5,20").first(), "the lowest of three minutes");
        equal(0, field(FieldType.MINUTE, "*").first(), "the lowest minute of a wildcard");
        equal(1, field(FieldType.DAY_OF_MONTH, "*").first(), "the lowest day of a wildcard");
    }

    // ---- value semantics --------------------------------------------------

    @Test("two fields parsed from the same text are equal")
    static void equality() {
        equal(field(FieldType.MINUTE, "0,30"), field(FieldType.MINUTE, "0,30"), "equal fields");
        equal(field(FieldType.MINUTE, "0,30").hashCode(),
                field(FieldType.MINUTE, "30,0").hashCode(),
                "the hash of the same set written two ways");
    }

    @Test("the same text in different fields is not equal")
    static void fieldTypeIsPartOfIdentity() {
        // Minute 1 and month 1 are both "bit zero set" under their own
        // minimums, so the type has to be part of identity or they collide.
        CronField minute = field(FieldType.MINUTE, "1");
        CronField hour = field(FieldType.HOUR, "1");

        isFalse(minute.equals(hour), "a minute to equal an hour");
    }
}
