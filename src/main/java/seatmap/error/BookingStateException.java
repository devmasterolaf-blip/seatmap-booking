package seatmap.error;

/** An operation does not fit the current state of a booking, e.g. cancelling it twice. */
public class BookingStateException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    public BookingStateException(String message) {
        super(message);
    }
}
