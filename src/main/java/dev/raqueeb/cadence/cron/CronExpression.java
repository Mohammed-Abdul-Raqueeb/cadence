package dev.raqueeb.cadence.cron;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A five-field cron expression, and when it next fires.
 *
 * <pre>
 *   ┌────────── minute       0-59
 *   │ ┌──────── hour         0-23
 *   │ │ ┌────── day-of-month 1-31
 *   │ │ │ ┌──── month        1-12 or JAN-DEC
 *   │ │ │ │ ┌── day-of-week  0-6  or SUN-SAT (0 and 7 are both Sunday)
 *   │ │ │ │ │
 *   * * * * *
 * </pre>
 *
 * <p>Seconds are deliberately absent. A scheduler that fires on the second
 * invites schedules it cannot honour — the queue, the journal write and the
 * handoff to a worker all cost more than the interval does — and the field
 * would be there to be misused rather than used.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class CronExpression {

    /**
     * How far ahead {@link #nextAfter} will look before giving up.
     *
     * <p>Eight years rather than one, because {@code 0 0 29 2 *} is legal and
     * fires only on 29 February. Leap years are usually four apart but a
     * century boundary stretches the gap to eight, so a shorter horizon would
     * report "never" for an expression that does fire.
     */
    private static final int HORIZON_YEARS = 8;

    /** Named shorthands, expanded before parsing. */
    private static final Map<String, String> MACROS = Map.of(
            "@yearly", "0 0 1 1 *",
            "@annually", "0 0 1 1 *",
            "@monthly", "0 0 1 * *",
            "@weekly", "0 0 * * 0",
            "@daily", "0 0 * * *",
            "@midnight", "0 0 * * *",
            "@hourly", "0 * * * *");

    private final CronField minute;
    private final CronField hour;
    private final CronField dayOfMonth;
    private final CronField month;
    private final CronField dayOfWeek;

    /** The expression as given, so round-tripping through a journal is exact. */
    private final String expression;

    private CronExpression(String expression, CronField minute, CronField hour,
            CronField dayOfMonth, CronField month, CronField dayOfWeek) {
        this.expression = expression;
        this.minute = minute;
        this.hour = hour;
        this.dayOfMonth = dayOfMonth;
        this.month = month;
        this.dayOfWeek = dayOfWeek;
    }

    /**
     * Parses a cron expression or a macro such as {@code @daily}.
     *
     * @param text the expression
     * @return the parsed expression
     * @throws CronParseException when the text is not a legal expression
     */
    public static CronExpression parse(String text) {
        if (text == null) {
            throw new CronParseException("null", null, null, "is null");
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new CronParseException(text, null, null, "is empty");
        }

        String expanded = trimmed;
        if (trimmed.charAt(0) == '@') {
            expanded = MACROS.get(trimmed.toLowerCase());
            if (expanded == null) {
                throw new CronParseException(text, null, trimmed,
                        "is not a known macro (try " + String.join(", ", sortedMacroNames()) + ")");
            }
        }

        // Splitting on runs of whitespace rather than a single space, because
        // expressions are often pasted out of an aligned crontab.
        String[] fields = expanded.split("\\s+");
        if (fields.length != 5) {
            throw new CronParseException(text, null, null,
                    "has " + fields.length + " fields; a cron expression has 5"
                            + " (minute hour day-of-month month day-of-week)");
        }

        return new CronExpression(trimmed,
                CronField.parse(FieldType.MINUTE, fields[0], text),
                CronField.parse(FieldType.HOUR, fields[1], text),
                CronField.parse(FieldType.DAY_OF_MONTH, fields[2], text),
                CronField.parse(FieldType.MONTH, fields[3], text),
                CronField.parse(FieldType.DAY_OF_WEEK, fields[4], text));
    }

    /**
     * The macro names, sorted, for an error message.
     *
     * @return the names in alphabetical order
     */
    private static List<String> sortedMacroNames() {
        return MACROS.keySet().stream().sorted().toList();
    }

    /**
     * Whether the expression fires at exactly this minute.
     *
     * <p>Seconds and nanoseconds on {@code when} are ignored, since the
     * expression has no second field to compare them against.
     *
     * @param when the instant to test
     * @return true when a fire is due at that minute
     */
    public boolean matches(LocalDateTime when) {
        return minute.matches(when.getMinute())
                && hour.matches(when.getHour())
                && month.matches(when.getMonthValue())
                && dayMatches(when.toLocalDate());
    }

    /**
     * The first fire time strictly after {@code from}.
     *
     * <p>Strictly after, so calling it with the time a job just fired yields
     * the following fire rather than the same one again. That is the property
     * the scheduler's loop depends on: were it inclusive, a job would
     * reschedule itself onto the instant it had just run and spin.
     *
     * @param from the instant to search after
     * @return the next fire time, or empty when the expression will not fire
     *     again within {@value #HORIZON_YEARS} years
     */
    public Optional<LocalDateTime> nextAfter(LocalDateTime from) {
        // Truncated to the minute and advanced by one, which is what makes
        // the result strictly after `from` even when `from` is itself a fire
        // time carrying seconds.
        LocalDateTime start = from.withSecond(0).withNano(0).plusMinutes(1);

        LocalDate date = start.toLocalDate();
        LocalDate limit = start.toLocalDate().plusYears(HORIZON_YEARS);
        int timeFloor = start.getHour() * 60 + start.getMinute();

        while (!date.isAfter(limit)) {
            if (!month.matches(date.getMonthValue())) {
                // Skipping to the first of the next month rather than a day at
                // a time: a schedule restricted to one month would otherwise
                // walk through eleven months of days to find it.
                date = date.withDayOfMonth(1).plusMonths(1);
                timeFloor = 0;
                continue;
            }

            if (dayMatches(date)) {
                int minuteOfDay = firstMinuteOfDayAtOrAfter(timeFloor);
                if (minuteOfDay >= 0) {
                    return Optional.of(date.atStartOfDay().plusMinutes(minuteOfDay));
                }
            }

            date = date.plusDays(1);
            timeFloor = 0;
        }

        return Optional.empty();
    }

    /**
     * The first matching minute-of-day at or after {@code floor}.
     *
     * @param floor minutes since midnight to start from
     * @return the matching minute-of-day, or -1 when the day has none left
     */
    private int firstMinuteOfDayAtOrAfter(int floor) {
        int floorHour = floor / 60;
        int floorMinute = floor % 60;

        for (int h = hour.nextAtOrAfter(floorHour); h >= 0; h = hour.nextAtOrAfter(h + 1)) {
            // The minute floor only applies within the hour the search began
            // in. Once an hour has been skipped, every minute of it is fair.
            int from = h == floorHour ? floorMinute : 0;
            int m = minute.nextAtOrAfter(from);
            if (m >= 0) {
                return h * 60 + m;
            }
        }

        return -1;
    }

    /**
     * Whether a date satisfies the two day fields.
     *
     * <p>This is the one genuinely surprising rule in cron, and it is not a
     * bug being reproduced for compatibility's sake — it is what makes the
     * two fields useful together. When day-of-month and day-of-week are
     * <em>both</em> restricted they are unioned, not intersected: {@code 0 0
     * 1 * MON} fires on the first of the month <em>and</em> on every Monday.
     * Intersecting them would make the pair almost always match nothing.
     *
     * <p>When only one is restricted, the other places no constraint, so the
     * result is just that field. This is why a literal {@code *} has to be
     * distinguished from a range that happens to cover everything.
     *
     * @param date the date to test
     * @return true when the date is a day the expression fires on
     */
    private boolean dayMatches(LocalDate date) {
        boolean byMonthDay = !dayOfMonth.isWildcard();
        boolean byWeekDay = !dayOfWeek.isWildcard();

        int monthDay = date.getDayOfMonth();
        // java.time counts Monday as 1 through Sunday as 7; cron counts
        // Sunday as 0, so Sunday's 7 wraps to 0 and the rest line up.
        int weekDay = date.getDayOfWeek().getValue() % 7;

        if (byMonthDay && byWeekDay) {
            return dayOfMonth.matches(monthDay) || dayOfWeek.matches(weekDay);
        }
        if (byMonthDay) {
            return dayOfMonth.matches(monthDay);
        }
        if (byWeekDay) {
            return dayOfWeek.matches(weekDay);
        }
        return true;
    }

    /**
     * The expression as it was written.
     *
     * @return the original text, trimmed
     */
    public String expression() {
        return expression;
    }

    /**
     * The minute field.
     *
     * @return the parsed minute field
     */
    public CronField minuteField() {
        return minute;
    }

    /**
     * The hour field.
     *
     * @return the parsed hour field
     */
    public CronField hourField() {
        return hour;
    }

    /**
     * The day-of-month field.
     *
     * @return the parsed day-of-month field
     */
    public CronField dayOfMonthField() {
        return dayOfMonth;
    }

    /**
     * The month field.
     *
     * @return the parsed month field
     */
    public CronField monthField() {
        return month;
    }

    /**
     * The day-of-week field.
     *
     * @return the parsed day-of-week field
     */
    public CronField dayOfWeekField() {
        return dayOfWeek;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CronExpression cron
                && minute.equals(cron.minute)
                && hour.equals(cron.hour)
                && dayOfMonth.equals(cron.dayOfMonth)
                && month.equals(cron.month)
                && dayOfWeek.equals(cron.dayOfWeek);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minute, hour, dayOfMonth, month, dayOfWeek);
    }

    @Override
    public String toString() {
        return expression;
    }
}
