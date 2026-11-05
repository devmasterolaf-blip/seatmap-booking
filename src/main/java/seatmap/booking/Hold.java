package seatmap.booking;

import java.time.Instant;
import java.util.List;

import seatmap.error.ValidationException;
import seatmap.venue.SeatId;

/**
 * A temporary claim on seats that blocks other customers until it is confirmed, released
 * or runs out at {@code expiresAt}.
 */
public record Hold(String id, String eventId, String customer, List<SeatId> seats, Instant createdAt,
        Instant expiresAt) {

    public Hold {
        if (id == null || eventId == null || customer == null || createdAt == null || expiresAt == null) {
            throw new ValidationException("a hold needs an id, event, customer and both timestamps");
        }
        if (seats == null || seats.isEmpty()) {
            throw new ValidationException("a hold needs at least one seat");
        }
        seats = List.copyOf(seats);
    }

    /** A hold is dead from the instant it expires; {@code expiresAt} itself is already too late. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
