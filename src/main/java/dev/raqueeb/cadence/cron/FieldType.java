package dev.raqueeb.cadence.cron;

import java.util.List;

/**
 * The five fields of a cron expression, in the order they are written.
 *
 * <p>Each knows its own legal range and its names, which is what lets the
 * parser be one algorithm rather than five special cases, and what lets a
 * parse error quote the range it violated.
 */
public enum FieldType {

    /** Minute of the hour, 0-59. */
    MINUTE("minute", 0, 59, List.of()),

    /** Hour of the day, 0-23. */
    HOUR("hour", 0, 23, List.of()),

    /** Day of the month, 1-31. */
    DAY_OF_MONTH("day-of-month", 1, 31, List.of()),

    /** Month of the year, 1-12, or JAN through DEC. */
    MONTH("month", 1, 12,
            List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")),

    /**
     * Day of the week, 0-6 with 0 as Sunday, or SUN through SAT.
     *
     * <p>7 is also accepted for Sunday and normalised to 0. Both conventions
     * are in the wild, and rejecting one of them would reject expressions
     * copied from working crontabs.
     */
    DAY_OF_WEEK("day-of-week", 0, 6,
            List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"));

    private final String fieldName;
    private final int min;
    private final int max;
    private final List<String> names;

    FieldType(String fieldName, int min, int max, List<String> names) {
        this.fieldName = fieldName;
        this.min = min;
        this.max = max;
        this.names = names;
    }

    /**
     * The field's name as an error message spells it.
     *
     * @return for example {@code day-of-month}
     */
    public String fieldName() {
        return fieldName;
    }

    /**
     * The lowest legal value.
     *
     * @return the inclusive lower bound
     */
    public int min() {
        return min;
    }

    /**
     * The highest legal value.
     *
     * @return the inclusive upper bound
     */
    public int max() {
        return max;
    }

    /**
     * How many distinct values the field can take.
     *
     * @return the width of the range, inclusive of both ends
     */
    public int width() {
        return max - min + 1;
    }

    /**
     * Whether the field accepts three-letter names.
     *
     * @return true for month and day-of-week
     */
    public boolean hasNames() {
        return !names.isEmpty();
    }

    /**
     * The accepted names, in value order.
     *
     * @return an immutable list, empty when the field is numeric only
     */
    public List<String> names() {
        return names;
    }

    /**
     * The numeric value of a name, case-insensitively.
     *
     * @param name a three-letter name such as {@code JAN} or {@code sun}
     * @return the value, or -1 when the name is not one of this field's
     */
    public int valueOfName(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return min + i;
            }
        }
        return -1;
    }

    /**
     * Whether a value falls inside the field's range.
     *
     * @param value the candidate
     * @return true when the value is legal for this field
     */
    public boolean accepts(int value) {
        return value >= min && value <= max;
    }

    /**
     * Normalises a value the field accepts under more than one convention.
     *
     * <p>Only day-of-week needs this, where 7 and 0 both mean Sunday.
     *
     * @param value the value as written
     * @return the canonical value
     */
    public int normalise(int value) {
        if (this == DAY_OF_WEEK && value == 7) {
            return 0;
        }
        return value;
    }

    /**
     * The highest value this field accepts before normalising.
     *
     * <p>Day-of-week accepts 7 even though its canonical range stops at 6.
     *
     * @return the inclusive upper bound as written
     */
    public int maxAsWritten() {
        return this == DAY_OF_WEEK ? 7 : max;
    }
}
