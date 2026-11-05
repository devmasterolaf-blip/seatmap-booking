package seatmap.error;

/** A layout or event file could not be parsed; carries the source name and line. */
public class ConfigParseException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    private final String source;
    private final int line;

    public ConfigParseException(String source, int line, String message) {
        super(source + ":" + line + ": " + message);
        this.source = source;
        this.line = line;
    }

    public String source() {
        return source;
    }

    /** One-based line number of the offending line. */
    public int line() {
        return line;
    }
}
