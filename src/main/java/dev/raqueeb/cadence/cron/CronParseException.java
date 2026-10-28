package dev.raqueeb.cadence.cron;

/**
 * A cron expression that could not be parsed, and where.
 *
 * <p>The position is carried because cron expressions are written by hand and
 * usually wrong in one field. "invalid cron expression" sends the author
 * back to count characters; naming the field and the offending token does
 * not.
 */
public class CronParseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The whole expression as it was given. */
    private final String expression;

    /** The field the error was found in, or null if the shape itself is wrong. */
    private final FieldType field;

    /** The token that could not be understood. */
    private final String token;

    /**
     * Creates a parse failure.
     *
     * @param expression the expression as given
     * @param field      the field at fault, or null when the whole shape is wrong
     * @param token      the offending token
     * @param reason     what is wrong with it, as a sentence fragment
     */
    public CronParseException(String expression, FieldType field, String token, String reason) {
        super(describe(expression, field, token, reason));
        this.expression = expression;
        this.field = field;
        this.token = token;
    }

    /**
     * The expression that failed to parse.
     *
     * @return the original expression
     */
    public String expression() {
        return expression;
    }

    /**
     * The field at fault.
     *
     * @return the field, or null when the expression's shape is wrong
     */
    public FieldType field() {
        return field;
    }

    /**
     * The token that could not be understood.
     *
     * @return the offending token
     */
    public String token() {
        return token;
    }

    /** Builds the message, naming the field and its legal range where known. */
    private static String describe(String expression, FieldType field, String token,
            String reason) {
        StringBuilder message = new StringBuilder();
        message.append("cannot parse cron expression \"").append(expression).append("\": ");

        if (field != null) {
            message.append("in the ").append(field.fieldName()).append(" field, ");
        }

        if (token != null && !token.isEmpty()) {
            message.append('"').append(token).append("\" ");
        }

        message.append(reason);

        if (field != null) {
            message.append(" (").append(field.fieldName()).append(" accepts ")
                    .append(field.min()).append('-').append(field.max());
            if (field.hasNames()) {
                message.append(" or ").append(String.join(", ", field.names()));
            }
            message.append(')');
        }

        return message.toString();
    }
}
