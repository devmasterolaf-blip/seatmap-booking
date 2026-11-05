package seatmap.error;

/** The event has started, so seats can no longer be held, bought or cancelled. */
public class SalesClosedException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    public SalesClosedException(String eventId) {
        super("event " + eventId + " has already started");
    }
}
