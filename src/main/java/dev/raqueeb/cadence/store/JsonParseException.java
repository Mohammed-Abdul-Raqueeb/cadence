package dev.raqueeb.cadence.store;

/**
 * Malformed JSON, and where in the document it went wrong.
 *
 * <p>The offset is carried because the documents this parser reads are
 * journal records, and a corrupt one is usually corrupt in a small way —
 * a truncated tail, a byte flipped on disk. "invalid JSON" against a
 * four-kilobyte record leaves an operator nothing to go on; an offset points
 * at the damage.
 */
public class JsonParseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Where in the document the parse failed, as a character offset. */
    private final int offset;

    /**
     * Creates a parse failure.
     *
     * @param reason what went wrong, as a sentence fragment
     * @param offset the character offset it went wrong at
     */
    public JsonParseException(String reason, int offset) {
        super("invalid JSON at offset " + offset + ": " + reason);
        this.offset = offset;
    }

    /**
     * Where the parse failed.
     *
     * @return the character offset
     */
    public int offset() {
        return offset;
    }
}
