package seatmap.cli;

import seatmap.error.SeatmapException;

/** The command line itself is wrong: unknown command or option, missing argument. */
public class UsageException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
        super(message);
    }
}
