package dev.raqueeb.cadence.store;

import dev.raqueeb.cadence.job.JobId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened to one job.
 *
 * <p>The journal is a log of events rather than a table of current state, and
 * that is the central decision in this package. A state table has to be
 * rewritten in place on every change, which means a crash can catch it
 * half-written and leave a job in no state at all. An append-only log can
 * only ever be damaged at its tail, which is recoverable: the records before
 * the damage are intact, and replaying them rebuilds exactly the state the
 * scheduler had before the last incomplete write.
 *
 * @param kind      what happened
 * @param jobId     which job it happened to
 * @param atMillis  when, in epoch milliseconds
 * @param data      the fields the kind carries
 */
public record JournalEntry(Kind kind, JobId jobId, long atMillis, Map<String, Object> data) {

    /** What kind of event a record describes. */
    public enum Kind {

        /** A job was submitted. Carries the whole spec. */
        SUBMITTED,

        /** A job's attempt began. */
        STARTED,

        /** An attempt finished without throwing. */
        SUCCEEDED,

        /** An attempt threw. Carries the error and whether a retry follows. */
        FAILED,

        /** A job was given a new fire time. */
        RESCHEDULED,

        /** A job was cancelled. */
        CANCELLED;

        /**
         * The lower-case name used on disk.
         *
         * @return the kind's wire name
         */
        public String wireName() {
            return name().toLowerCase();
        }

        /**
         * Parses a kind from its wire name.
         *
         * @param wireName the name as written on disk
         * @return the kind
         * @throws IllegalArgumentException when the name is not a kind
         */
        public static Kind fromWireName(String wireName) {
            if (wireName != null) {
                for (Kind kind : values()) {
                    if (kind.wireName().equals(wireName)) {
                        return kind;
                    }
                }
            }
            throw new IllegalArgumentException("not a journal entry kind: " + wireName);
        }
    }

    /**
     * Validates the record.
     *
     * @throws IllegalArgumentException when a required field is missing
     */
    public JournalEntry {
        if (kind == null || jobId == null) {
            throw new IllegalArgumentException("a journal entry needs a kind and a job id");
        }
        if (data == null) {
            data = Map.of();
        }
    }

    /**
     * Creates an entry carrying no extra fields.
     *
     * @param kind     what happened
     * @param jobId    which job
     * @param atMillis when
     * @return the entry
     */
    public static JournalEntry of(Kind kind, JobId jobId, long atMillis) {
        return new JournalEntry(kind, jobId, atMillis, Map.of());
    }

    /**
     * Renders the entry as a JSON object.
     *
     * <p>The kind, id and timestamp are written as named fields rather than
     * merged into {@code data}, so a field named {@code kind} in the payload
     * cannot shadow the record's own.
     *
     * @return the JSON text
     */
    public String toJson() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("kind", kind.wireName());
        object.put("job", jobId.text());
        object.put("at", atMillis);
        if (!data.isEmpty()) {
            object.put("data", data);
        }
        return Json.write(object);
    }

    /**
     * Reads an entry from a JSON object.
     *
     * @param json the JSON text
     * @return the entry
     * @throws JsonParseException when the text is not a well-formed entry
     */
    public static JournalEntry fromJson(String json) {
        Map<String, Object> object = Json.parseObject(json);

        Object kind = object.get("kind");
        Object job = object.get("job");
        Object at = object.get("at");

        if (!(kind instanceof String kindText) || !(job instanceof String jobText)
                || !(at instanceof Long atMillis)) {
            throw new JsonParseException(
                    "a journal entry needs a string kind, a string job and an integer at,"
                            + " found " + object.keySet(), 0);
        }

        Map<String, Object> data = Map.of();
        if (object.get("data") instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) raw;
            data = typed;
        }

        try {
            return new JournalEntry(Kind.fromWireName(kindText), JobId.parse(jobText),
                    atMillis.longValue(), data);
        } catch (IllegalArgumentException e) {
            // A bad kind or a malformed id is a corrupt record, not a
            // programming error in the caller, so it is reported as a parse
            // failure like any other damage.
            throw new JsonParseException(e.getMessage(), 0);
        }
    }

    /**
     * A field from {@link #data} as a string.
     *
     * @param key the field name
     * @return the value, or null when absent or not a string
     */
    public String string(String key) {
        return data.get(key) instanceof String value ? value : null;
    }

    /**
     * A field from {@link #data} as a long.
     *
     * @param key      the field name
     * @param fallback what to return when the field is absent or not a number
     * @return the value, or {@code fallback}
     */
    public long number(String key, long fallback) {
        return data.get(key) instanceof Number value ? value.longValue() : fallback;
    }

    /**
     * A field from {@link #data} as a boolean.
     *
     * @param key      the field name
     * @param fallback what to return when the field is absent or not a boolean
     * @return the value, or {@code fallback}
     */
    public boolean flag(String key, boolean fallback) {
        return data.get(key) instanceof Boolean value ? value.booleanValue() : fallback;
    }
}
