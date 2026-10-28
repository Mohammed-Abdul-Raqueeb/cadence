package dev.raqueeb.cadence.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON reader and writer, in the subset a journal needs.
 *
 * <p>Values map onto plain Java types: object to {@link LinkedHashMap},
 * array to {@link ArrayList}, string to {@link String}, number to
 * {@link Double} or {@link Long}, and the literals to {@link Boolean} and
 * null. No annotations, no reflection, no binding to user classes — the
 * journal writes a handful of known shapes, so a mapper would be a great
 * deal of machinery for a job this small.
 *
 * <p>What it does not do: comments, trailing commas, single quotes,
 * unquoted keys, or any other JSON5 liberty. A journal is written by this
 * code and read by this code, so accepting malformed input would only hide
 * a bug in the writer.
 *
 * <p>Numbers are read as {@link Long} when they have no fraction or exponent
 * and fit, and as {@link Double} otherwise. That matters for a journal:
 * epoch milliseconds read back as a double lose precision above 2^53, and
 * silently drifting timestamps are a bad way to find out.
 */
public final class Json {

    /**
     * How deeply objects and arrays may nest.
     *
     * <p>A bound rather than recursion to the stack's limit. The parser is
     * recursive, so a document of ten thousand open brackets would otherwise
     * be a StackOverflowError — which is an {@link Error}, not an exception,
     * and so escapes the catch block that was meant to contain a parse
     * failure.
     */
    public static final int MAX_DEPTH = 64;

    private Json() {
    }

    // ---- writing ----------------------------------------------------------

    /**
     * Renders a value as JSON.
     *
     * @param value a map, list, string, number, boolean or null
     * @return the JSON text, on one line
     * @throws IllegalArgumentException when the value is of a type with no
     *     JSON equivalent
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, 0);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "value nests deeper than " + MAX_DEPTH + " levels");
        }

        switch (value) {
            case null -> out.append("null");
            case String string -> writeString(string, out);
            case Boolean bool -> out.append(bool.booleanValue());
            case Double number -> writeDouble(number, out);
            case Float number -> writeDouble(number.doubleValue(), out);
            case Number number -> out.append(number.longValue());
            case Map<?, ?> map -> writeObject(map, out, depth);
            case Iterable<?> list -> writeArray(list, out, depth);
            default -> throw new IllegalArgumentException(
                    "no JSON equivalent for " + value.getClass().getName()
                            + "; convert it to a map, list, string or number first");
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out, int depth) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("a JSON object cannot have a null key");
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(entry.getKey().toString(), out);
            out.append(':');
            writeValue(entry.getValue(), out, depth + 1);
        }
        out.append('}');
    }

    private static void writeArray(Iterable<?> list, StringBuilder out, int depth) {
        out.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(element, out, depth + 1);
        }
        out.append(']');
    }

    private static void writeDouble(double number, StringBuilder out) {
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            // JSON has no way to spell these. Writing `NaN` would produce a
            // document this parser -- and every other -- rejects, so the
            // failure belongs here where the offending value is in hand.
            throw new IllegalArgumentException("JSON has no representation for " + number);
        }
        out.append(number);
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Everything below 0x20 must be escaped, and a raw
                    // newline inside a string would break the journal's
                    // one-record-per-line framing even where JSON allowed it.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---- reading ----------------------------------------------------------

    /**
     * Parses a JSON document.
     *
     * @param text the document
     * @return the value, as plain Java types
     * @throws JsonParseException when the text is not valid JSON
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonParseException("cannot parse null", 0);
        }

        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();

        if (!parser.atEnd()) {
            // Trailing content means the document was not what the caller
            // thought it was -- two concatenated records, most likely -- and
            // ignoring it would silently drop the second.
            throw new JsonParseException(
                    "unexpected content after the value: " + parser.rest(), parser.position());
        }
        return value;
    }

    /**
     * Parses a document expected to be an object.
     *
     * @param text the document
     * @return the object as a map
     * @throws JsonParseException when the text is not a JSON object
     */
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new JsonParseException("expected a JSON object, found "
                + (value == null ? "null" : value.getClass().getSimpleName()), 0);
    }

    /** A recursive-descent parser over a string. */
    private static final class Parser {

        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        int position() {
            return at;
        }

        boolean atEnd() {
            return at >= text.length();
        }

        String rest() {
            String remaining = text.substring(at);
            return remaining.length() <= 20 ? remaining : remaining.substring(0, 20) + "...";
        }

        void skipWhitespace() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    at++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonParseException(
                        "nesting deeper than " + MAX_DEPTH + " levels", at);
            }
            if (atEnd()) {
                throw new JsonParseException("expected a value but the document ended", at);
            }

            char c = text.charAt(at);
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject(int depth) {
            expect('{');
            // Insertion-ordered so a record round-trips byte for byte, which
            // is what lets a test compare written output against a fixture.
            Map<String, Object> object = new LinkedHashMap<>();

            skipWhitespace();
            if (peekIs('}')) {
                at++;
                return object;
            }

            while (true) {
                skipWhitespace();
                if (!peekIs('"')) {
                    throw new JsonParseException("expected a quoted key, found " + describeHere(),
                            at);
                }
                String key = readString();

                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, readValue(depth + 1));

                skipWhitespace();
                if (peekIs(',')) {
                    at++;
                    continue;
                }
                if (peekIs('}')) {
                    at++;
                    return object;
                }
                throw new JsonParseException(
                        "expected ',' or '}' in an object, found " + describeHere(), at);
            }
        }

        private List<Object> readArray(int depth) {
            expect('[');
            List<Object> array = new ArrayList<>();

            skipWhitespace();
            if (peekIs(']')) {
                at++;
                return array;
            }

            while (true) {
                skipWhitespace();
                array.add(readValue(depth + 1));

                skipWhitespace();
                if (peekIs(',')) {
                    at++;
                    continue;
                }
                if (peekIs(']')) {
                    at++;
                    return array;
                }
                throw new JsonParseException(
                        "expected ',' or ']' in an array, found " + describeHere(), at);
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();

            while (true) {
                if (atEnd()) {
                    throw new JsonParseException("a string was not closed", at);
                }
                char c = text.charAt(at++);

                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw new JsonParseException(
                                "a raw control character (0x" + Integer.toHexString(c)
                                        + ") is not allowed in a string", at - 1);
                    }
                    out.append(c);
                    continue;
                }

                if (atEnd()) {
                    throw new JsonParseException("a string ended inside an escape", at);
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> out.append(readUnicodeEscape());
                    default -> throw new JsonParseException(
                            "'\\" + escape + "' is not a valid escape", at - 1);
                }
            }
        }

        private char readUnicodeEscape() {
            if (at + 4 > text.length()) {
                throw new JsonParseException("a \\u escape needs four hex digits", at);
            }
            String hex = text.substring(at, at + 4);
            for (int i = 0; i < 4; i++) {
                if (Character.digit(hex.charAt(i), 16) < 0) {
                    throw new JsonParseException(
                            "'" + hex + "' is not four hex digits", at);
                }
            }
            at += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        private Object readNumber() {
            int start = at;

            if (peekIs('-')) {
                at++;
            }
            boolean integral = true;
            while (!atEnd()) {
                char c = text.charAt(at);
                if (c >= '0' && c <= '9') {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    at++;
                } else {
                    break;
                }
            }

            String token = text.substring(start, at);
            if (token.isEmpty() || "-".equals(token)) {
                throw new JsonParseException("expected a number, found " + describeAt(start),
                        start);
            }

            try {
                if (integral) {
                    // Kept as a long where it fits. A journal's epoch
                    // milliseconds read back as a double lose precision above
                    // 2^53, and a timestamp that drifts is a bad way to learn
                    // the parser widened it.
                    return Long.valueOf(token);
                }
                return Double.valueOf(token);
            } catch (NumberFormatException e) {
                // Out of long range, or a malformed exponent. Falling back to
                // double is right for the first and the retry fails loudly
                // for the second.
                try {
                    return Double.valueOf(token);
                } catch (NumberFormatException stillBad) {
                    throw new JsonParseException("'" + token + "' is not a number", start);
                }
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, at)) {
                throw new JsonParseException(
                        "expected " + literal + ", found " + describeHere(), at);
            }
            at += literal.length();
            return value;
        }

        private boolean peekIs(char expected) {
            return at < text.length() && text.charAt(at) == expected;
        }

        private void expect(char expected) {
            if (!peekIs(expected)) {
                throw new JsonParseException(
                        "expected '" + expected + "', found " + describeHere(), at);
            }
            at++;
        }

        private String describeHere() {
            return describeAt(at);
        }

        private String describeAt(int index) {
            if (index >= text.length()) {
                return "the end of the document";
            }
            return "'" + text.charAt(index) + "'";
        }
    }
}
