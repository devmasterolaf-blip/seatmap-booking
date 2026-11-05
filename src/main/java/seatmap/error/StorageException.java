package seatmap.error;

/** A data file could not be read or written, or its contents are corrupt. */
public class StorageException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
