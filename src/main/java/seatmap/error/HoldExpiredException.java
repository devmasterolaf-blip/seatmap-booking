package seatmap.error;

import java.time.Instant;

/** A hold was used after its expiry time. */
public class HoldExpiredException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    private final String holdId;

    public HoldExpiredException(String holdId, Instant expiredAt) {
        super("hold " + holdId + " expired at " + expiredAt);
        this.holdId = holdId;
    }

    public String holdId() {
        return holdId;
    }
}
