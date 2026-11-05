package seatmap.error;

/** Base type for every error raised by the reservation engine. */
public class SeatmapException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SeatmapException(String message) {
        super(message);
    }

    public SeatmapException(String message, Throwable cause) {
        super(message, cause);
    }
}
