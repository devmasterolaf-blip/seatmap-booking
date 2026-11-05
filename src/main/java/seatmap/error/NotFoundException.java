package seatmap.error;

/** A referenced event, seat, hold or booking does not exist. */
public class NotFoundException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    private final String kind;
    private final String id;

    public NotFoundException(String kind, String id) {
        super("unknown " + kind + " '" + id + "'");
        this.kind = kind;
        this.id = id;
    }

    /** What was looked up, e.g. {@code "event"} or {@code "booking"}. */
    public String kind() {
        return kind;
    }

    public String id() {
        return id;
    }
}
