package dev.raqueeb.cadence.cron;

import java.util.ArrayList;
import java.util.List;

/**
 * One parsed cron field, as the set of values it matches.
 *
 * <p>The set is a bitmask in a single {@code long}. The widest field is the
 * minute, with sixty values, which fits exactly — so matching is one shift
 * and one mask, and finding the next matching value is a shift and a
 * {@link Long#numberOfTrailingZeros}. That matters because computing the next
 * fire time walks fields repeatedly, and a set-of-Integer there turns a
 * pointer chase into the dominant cost of scheduling.
 *
 * <p>Instances are immutable.
 */
public final class CronField {

    /** Bit {@code i} is set when {@code type.min() + i} matches. */
    private final long bits;

    /** The field this was parsed for, kept for error messages and bounds. */
    private final FieldType type;

    /**
     * Whether the field was written as a bare {@code *}.
     *
     * <p>Tracked separately from "matches everything", because the two differ
     * and the difference is load-bearing. {@code 0-6} on day-of-week also
     * matches everything, but only a literal {@code *} means "unrestricted",
     * which is what decides whether day-of-month and day-of-week are
     * intersected or unioned.
     */
    private final boolean wildcard;

    private CronField(FieldType type, long bits, boolean wildcard) {
        this.type = type;
        this.bits = bits;
        this.wildcard = wildcard;
    }

    /**
     * Parses one field of a cron expression.
     *
     * @param type       which field this is
     * @param text       the field's text, such as {@code 0}, {@code 9-17} or {@code 1,15,30}
     * @param expression the whole expression, for error messages
     * @return the parsed field
     * @throws CronParseException when the text is not a legal field
     */
    public static CronField parse(FieldType type, String text, String expression) {
        if (text == null || text.isBlank()) {
            throw new CronParseException(expression, type, text, "is empty");
        }

        boolean wildcard = "*".equals(text);
        long bits = 0L;

        // A list is the outermost construct, so it is split first and each
        // part parsed independently. Nesting a list inside a range has no
        // meaning, which is why this is a split rather than a recursion.
        for (String part : text.split(",", -1)) {
            if (part.isBlank()) {
                throw new CronParseException(expression, type, text,
                        "has an empty entry in its list");
            }
            bits |= parsePart(type, part.trim(), text, expression);
        }

        if (bits == 0L) {
            // Unreachable through the branches above, which all either set a
            // bit or throw. Kept as a guard rail: a field matching nothing
            // would make the schedule silently never fire.
            throw new CronParseException(expression, type, text, "matches no values");
        }

        return new CronField(type, bits, wildcard);
    }

    /** Parses one comma-separated part: a value, a range, or either with a step. */
    private static long parsePart(FieldType type, String part, String field, String expression) {
        String range = part;
        int step = 1;

        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            String stepText = part.substring(slash + 1);
            if (stepText.isEmpty()) {
                throw new CronParseException(expression, type, part, "has a / with no step after it");
            }
            step = parseNumber(type, stepText, part, expression, "step");
            if (step < 1) {
                throw new CronParseException(expression, type, part,
                        "has a step of " + step + "; a step must be at least 1");
            }
            if (step > type.width()) {
                throw new CronParseException(expression, type, part,
                        "has a step of " + step + ", which is wider than the field");
            }
        }

        int from;
        int to;

        if ("*".equals(range)) {
            from = type.min();
            to = type.max();
        } else {
            int dash = range.indexOf('-', 1);
            if (dash >= 0) {
                from = parseValue(type, range.substring(0, dash), part, expression);
                to = parseValue(type, range.substring(dash + 1), part, expression);
            } else {
                from = parseValue(type, range, part, expression);
                // `a/n` means "from a to the end of the field, every n". Bare
                // `a` is a single value, so the step decides which this is.
                to = slash >= 0 ? type.max() : from;
            }
        }

        if (from > to) {
            // A wrapping range such as FRI-MON is rejected rather than
            // guessed at. Some cron implementations wrap and some do not, so
            // accepting it would mean the same expression behaves differently
            // here than wherever it was copied from.
            throw new CronParseException(expression, type, part,
                    "runs from " + from + " down to " + to
                            + "; a range must ascend, so write two entries instead");
        }

        long bits = 0L;
        for (int value = from; value <= to; value += step) {
            bits |= 1L << (value - type.min());
        }
        return bits;
    }

    /** Parses a single value, accepting a name where the field allows one. */
    private static int parseValue(FieldType type, String text, String part, String expression) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new CronParseException(expression, type, part, "is missing a value");
        }

        if (type.hasNames() && !isDigits(trimmed)) {
            int named = type.valueOfName(trimmed);
            if (named < 0) {
                throw new CronParseException(expression, type, trimmed, "is not a recognised name");
            }
            return named;
        }

        int value = parseNumber(type, trimmed, part, expression, "value");
        if (value < type.min() || value > type.maxAsWritten()) {
            throw new CronParseException(expression, type, trimmed, "is out of range");
        }
        return type.normalise(value);
    }

    /** Parses an integer, reporting the field rather than letting NumberFormatException out. */
    private static int parseNumber(FieldType type, String text, String part, String expression,
            String what) {
        if (!isDigits(text)) {
            throw new CronParseException(expression, type, text, "is not a number where a "
                    + what + " was expected");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            // Digits that overflow an int, such as a 20-digit step.
            throw new CronParseException(expression, type, text, "is too large to be a " + what);
        }
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a value matches this field.
     *
     * @param value the candidate, in the field's own units
     * @return true when the field matches it
     */
    public boolean matches(int value) {
        if (!type.accepts(value)) {
            return false;
        }
        return (bits & (1L << (value - type.min()))) != 0;
    }

    /**
     * The lowest matching value at or after {@code value}.
     *
     * @param value where to start looking, in the field's own units
     * @return the matching value, or -1 when the field has none that high
     */
    public int nextAtOrAfter(int value) {
        int from = Math.max(value, type.min());
        if (from > type.max()) {
            return -1;
        }

        long remaining = bits >>> (from - type.min());
        if (remaining == 0L) {
            return -1;
        }
        return from + Long.numberOfTrailingZeros(remaining);
    }

    /**
     * The lowest value this field matches.
     *
     * @return the first matching value
     */
    public int first() {
        return type.min() + Long.numberOfTrailingZeros(bits);
    }

    /**
     * Whether the field was written as a bare {@code *}.
     *
     * @return true only for a literal asterisk
     */
    public boolean isWildcard() {
        return wildcard;
    }

    /**
     * How many values the field matches.
     *
     * @return the number of matching values
     */
    public int count() {
        return Long.bitCount(bits);
    }

    /**
     * Every value this field matches, ascending.
     *
     * @return an immutable list of matching values
     */
    public List<Integer> values() {
        List<Integer> values = new ArrayList<>(count());
        for (int value = first(); value >= 0; value = nextAtOrAfter(value + 1)) {
            values.add(value);
        }
        return List.copyOf(values);
    }

    /**
     * The field this was parsed for.
     *
     * @return the field type
     */
    public FieldType type() {
        return type;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CronField field
                && field.type == type
                && field.bits == bits
                && field.wildcard == wildcard;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bits) * 31 + type.hashCode();
    }

    @Override
    public String toString() {
        return wildcard ? "*" : values().toString();
    }
}
