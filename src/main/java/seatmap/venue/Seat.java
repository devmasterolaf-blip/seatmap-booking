package seatmap.venue;

import seatmap.error.ValidationException;

/**
 * One physical seat.
 *
 * @param id         address of the seat
 * @param category   code of the seat category
 * @param accessible whether the seat is a wheelchair or companion position
 * @param position   zero-based index of the seat inside its row, counted from the left
 * @param block      zero-based index of the aisle-delimited block the seat belongs to;
 *                   two seats are neighbours only when they share a block
 */
public record Seat(SeatId id, String category, boolean accessible, int position, int block) {

    public Seat {
        if (id == null) {
            throw new ValidationException("seat id must not be null");
        }
        Identifiers.requireCode(category, "category code");
        if (position < 0 || block < 0) {
            throw new ValidationException("seat position and block must not be negative");
        }
    }

    /** True when the two seats sit side by side with no aisle between them. */
    public boolean isAdjacentTo(Seat other) {
        return id.section().equals(other.id.section())
                && id.row().equals(other.id.row())
                && block == other.block
                && Math.abs(position - other.position) == 1;
    }
}
