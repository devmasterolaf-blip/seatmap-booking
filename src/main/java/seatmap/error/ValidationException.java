package seatmap.error;

/** An argument or a piece of configuration violates a documented constraint. */
public class ValidationException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }
}
