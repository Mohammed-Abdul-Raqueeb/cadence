package dev.raqueeb.cadence.store;

import static dev.raqueeb.cadence.harness.Assert.contains;
import static dev.raqueeb.cadence.harness.Assert.equal;
import static dev.raqueeb.cadence.harness.Assert.isTrue;
import static dev.raqueeb.cadence.harness.Assert.throws_;

import dev.raqueeb.cadence.harness.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The hand-rolled JSON reader and writer. */
final class JsonTest {

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    // ---- writing ----------------------------------------------------------

    @Test("primitives write as JSON literals")
    static void writesPrimitives() {
        equal("null", Json.write(null), "null");
        equal("true", Json.write(Boolean.TRUE), "true");
        equal("false", Json.write(Boolean.FALSE), "false");
        equal("\"hello\"", Json.write("hello"), "a string");
        equal("42", Json.write(42), "an int");
        equal("42", Json.write(42L), "a long");
    }

    @Test("an object writes its keys in insertion order")
    static void writesObjectsInOrder() {
        // Insertion order is what lets a journal record round-trip byte for
        // byte, so a fixture comparison is meaningful.
        equal("{\"b\":1,\"a\":2}", Json.write(map("b", 1, "a", 2)), "the written object");
    }

    @Test("empty containers write as empty")
    static void writesEmptyContainers() {
        equal("{}", Json.write(map()), "an empty object");
        equal("[]", Json.write(List.of()), "an empty array");
    }

    @Test("nested containers write recursively")
    static void writesNested() {
        Object value = map("jobs", List.of(map("id", "A"), map("id", "B")));

        equal("{\"jobs\":[{\"id\":\"A\"},{\"id\":\"B\"}]}", Json.write(value), "the nested value");
    }

    @Test("strings escape quotes, backslashes and control characters")
    static void writesEscapes() {
        equal("\"a\\\"b\"", Json.write("a\"b"), "a quote");
        equal("\"a\\\\b\"", Json.write("a\\b"), "a backslash");
        equal("\"a\\nb\"", Json.write("a\nb"), "a newline");
        equal("\"a\\tb\"", Json.write("a\tb"), "a tab");
        equal("\"a\\rb\"", Json.write("a\rb"), "a carriage return");
    }

    @Test("an unusual control character escapes as \\u")
    static void writesUnicodeEscapes() {
        // 0x01 has no short escape, and a raw one would break the journal's
        // one-record-per-line framing as surely as a newline would.
        equal("\"a\\u0001b\"", Json.write("a\u0001b"), "a control character");
    }

    @Test("NaN and infinity are refused rather than written")
    static void refusesNonFiniteNumbers() {
        // JSON cannot spell them. Writing `NaN` would produce a document
        // every parser rejects, so the failure belongs here, where the
        // offending value is still in hand.
        IllegalArgumentException error = throws_(IllegalArgumentException.class,
                () -> Json.write(Double.NaN));
        contains(error.getMessage(), "no representation", "the message says why");

        throws_(IllegalArgumentException.class, () -> Json.write(Double.POSITIVE_INFINITY));
    }

    @Test("a type with no JSON equivalent is refused by name")
    static void refusesUnknownTypes() {
        IllegalArgumentException error = throws_(IllegalArgumentException.class,
                () -> Json.write(new Object()));

        contains(error.getMessage(), "java.lang.Object", "the message names the type");
    }

    @Test("a null object key is refused")
    static void refusesNullKeys() {
        Map<String, Object> withNullKey = new LinkedHashMap<>();
        withNullKey.put(null, 1);

        throws_(IllegalArgumentException.class, () -> Json.write(withNullKey));
    }

    @Test("writing refuses to nest past the depth limit")
    static void writeDepthIsBounded() {
        List<Object> nested = new ArrayList<>();
        List<Object> cursor = nested;
        for (int i = 0; i < Json.MAX_DEPTH + 5; i++) {
            List<Object> inner = new ArrayList<>();
            cursor.add(inner);
            cursor = inner;
        }

        throws_(IllegalArgumentException.class, () -> Json.write(nested));
    }

    // ---- reading ----------------------------------------------------------

    @Test("primitives parse to Java types")
    static void parsesPrimitives() {
        equal(null, Json.parse("null"), "null");
        equal(Boolean.TRUE, Json.parse("true"), "true");
        equal(Boolean.FALSE, Json.parse("false"), "false");
        equal("hello", Json.parse("\"hello\""), "a string");
    }

    @Test("an integer parses as a long, not a double")
    static void integersStayLongs() {
        // The one that matters for a journal: epoch milliseconds read back as
        // a double lose precision above 2^53, and a timestamp that silently
        // drifts is a bad way to discover the parser widened it.
        Object parsed = Json.parse("1793000000000");

        isTrue(parsed instanceof Long, "1793000000000 to parse as a Long, got "
                + parsed.getClass().getSimpleName());
        equal(1_793_000_000_000L, parsed, "the value");
    }

    @Test("a large epoch millisecond value survives a round trip exactly")
    static void largeTimestampsRoundTrip() {
        long millis = 9_007_199_254_740_993L;

        equal(millis, Json.parse(Json.write(millis)), "a value above 2^53");
    }

    @Test("a fractional or exponential number parses as a double")
    static void fractionsBecomeDoubles() {
        isTrue(Json.parse("1.5") instanceof Double, "1.5 to parse as a Double");
        isTrue(Json.parse("1e3") instanceof Double, "1e3 to parse as a Double");
        equal(Double.valueOf(1.5), Json.parse("1.5"), "the value");
    }

    @Test("a negative number parses")
    static void negativeNumbers() {
        equal(-7L, Json.parse("-7"), "a negative integer");
        equal(Double.valueOf(-0.5), Json.parse("-0.5"), "a negative fraction");
    }

    @Test("objects and arrays parse, nested")
    static void parsesContainers() {
        Object parsed = Json.parse("{\"a\":[1,2,{\"b\":true}]}");

        equal(map("a", List.of(1L, 2L, map("b", Boolean.TRUE))), parsed, "the parsed value");
    }

    @Test("whitespace between tokens is ignored")
    static void whitespaceIsIgnored() {
        equal(map("a", 1L), Json.parse("  {\n  \"a\"  :\t1\r\n}  "), "a padded document");
    }

    @Test("escapes parse back to their characters")
    static void parsesEscapes() {
        equal("a\"b", Json.parse("\"a\\\"b\""), "a quote");
        equal("a\\b", Json.parse("\"a\\\\b\""), "a backslash");
        equal("a\nb", Json.parse("\"a\\nb\""), "a newline");
        equal("a/b", Json.parse("\"a\\/b\""), "an escaped solidus");
        equal("a\u0001b", Json.parse("\"a\\u0001b\""), "a unicode escape");
        equal("\u00e9", Json.parse("\"\\u00e9\""), "an accented character");
    }

    @Test("parseObject returns a map and rejects anything else")
    static void parseObjectIsTyped() {
        equal(map("a", 1L), Json.parseObject("{\"a\":1}"), "an object");

        JsonParseException error =
                throws_(JsonParseException.class, () -> Json.parseObject("[1,2]"));
        contains(error.getMessage(), "expected a JSON object", "the message says what was wanted");
    }

    // ---- round trips ------------------------------------------------------

    @Test("every value written parses back equal")
    static void roundTrips() {
        List<Object> cases = List.of(
                map(),
                map("id", "00001M61FS6AA0QV", "state", "scheduled", "attempts", 0L),
                map("nested", map("deep", List.of(1L, 2L, 3L))),
                map("text", "a \"quoted\" line\nwith a break\tand a tab"),
                map("flags", List.of(Boolean.TRUE, Boolean.FALSE)),
                List.of("a", "b", "c"),
                "a plain string",
                42L);

        for (Object value : cases) {
            equal(value, Json.parse(Json.write(value)), "the round trip of " + Json.write(value));
        }
    }

    @Test("a null inside a container round-trips")
    static void nullInsideContainersRoundTrips() {
        Map<String, Object> withNull = map("error", null, "id", "A");

        equal(withNull, Json.parse(Json.write(withNull)), "an object holding a null");
    }

    // ---- malformed input --------------------------------------------------

    @Test("an unterminated string is rejected")
    static void unterminatedString() {
        JsonParseException error =
                throws_(JsonParseException.class, () -> Json.parse("\"unclosed"));

        contains(error.getMessage(), "not closed", "the message says what is wrong");
    }

    @Test("an unterminated object or array is rejected")
    static void unterminatedContainer() {
        throws_(JsonParseException.class, () -> Json.parse("{\"a\":1"));
        throws_(JsonParseException.class, () -> Json.parse("[1,2"));
    }

    @Test("a trailing comma is rejected")
    static void trailingCommaIsRejected() {
        // The journal is written by this code and read by this code, so
        // accepting a liberty here would only hide a bug in the writer.
        throws_(JsonParseException.class, () -> Json.parse("{\"a\":1,}"));
        throws_(JsonParseException.class, () -> Json.parse("[1,2,]"));
    }

    @Test("an unquoted key is rejected")
    static void unquotedKeyIsRejected() {
        JsonParseException error =
                throws_(JsonParseException.class, () -> Json.parse("{a:1}"));

        contains(error.getMessage(), "quoted key", "the message says what was expected");
    }

    @Test("a single-quoted string is rejected")
    static void singleQuotesAreRejected() {
        throws_(JsonParseException.class, () -> Json.parse("'a'"));
    }

    @Test("content after the value is rejected rather than ignored")
    static void trailingContentIsRejected() {
        // Two concatenated records are the likely cause, and ignoring the
        // second would silently drop a journal entry.
        JsonParseException error =
                throws_(JsonParseException.class, () -> Json.parse("{\"a\":1}{\"b\":2}"));

        contains(error.getMessage(), "unexpected content", "the message says what is wrong");
    }

    @Test("a raw control character inside a string is rejected")
    static void rawControlCharacterIsRejected() {
        JsonParseException error =
                throws_(JsonParseException.class, () -> Json.parse("\"a\nb\""));

        contains(error.getMessage(), "control character", "the message says what is wrong");
    }

    @Test("an unknown escape is rejected, quoting it")
    static void unknownEscapeIsRejected() {
        JsonParseException error =
                throws_(JsonParseException.class, () -> Json.parse("\"a\\qb\""));

        contains(error.getMessage(), "\\q", "the message quotes the escape");
    }

    @Test("a short or non-hex unicode escape is rejected")
    static void badUnicodeEscapeIsRejected() {
        throws_(JsonParseException.class, () -> Json.parse("\"\\u00\""));
        throws_(JsonParseException.class, () -> Json.parse("\"\\uZZZZ\""));
    }

    @Test("an empty document is rejected")
    static void emptyDocumentIsRejected() {
        throws_(JsonParseException.class, () -> Json.parse(""));
        throws_(JsonParseException.class, () -> Json.parse("   "));
        throws_(JsonParseException.class, () -> Json.parse(null));
    }

    @Test("a bare minus is rejected as a number")
    static void bareMinusIsRejected() {
        throws_(JsonParseException.class, () -> Json.parse("-"));
    }

    @Test("rubbish is rejected")
    static void rubbishIsRejected() {
        throws_(JsonParseException.class, () -> Json.parse("tru"));
        throws_(JsonParseException.class, () -> Json.parse("nul"));
        throws_(JsonParseException.class, () -> Json.parse("@"));
    }

    @Test("a deeply nested document is rejected rather than overflowing the stack")
    static void parseDepthIsBounded() {
        // The parser is recursive, so without the bound this is a
        // StackOverflowError -- which is an Error, not an exception, and so
        // escapes a catch meant to contain a parse failure.
        String deep = "[".repeat(Json.MAX_DEPTH + 10) + "]".repeat(Json.MAX_DEPTH + 10);

        JsonParseException error = throws_(JsonParseException.class, () -> Json.parse(deep));
        contains(error.getMessage(), "nesting deeper", "the message says why");
    }

    @Test("the failure offset points into the document")
    static void offsetIsReported() {
        JsonParseException error =
                throws_(JsonParseException.class, () -> Json.parse("{\"a\":1,\"b\":}"));

        isTrue(error.offset() > 0, "the offset to be past the start, got " + error.offset());
        contains(error.getMessage(), "offset", "the message carries the offset");
    }
}
