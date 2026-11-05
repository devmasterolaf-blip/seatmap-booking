package seatmap.error;

import java.util.List;

import seatmap.venue.SeatId;

/** One or more requested seats are held or booked by someone else. */
public class SeatUnavailableException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    private final transient List<SeatId> seats;

    public SeatUnavailableException(String eventId, List<SeatId> seats) {
        super((seats.size() == 1 ? "seat " + seats.get(0) + " is" : "seats " + join(seats) + " are")
                + " not available for event " + eventId);
        this.seats = List.copyOf(seats);
    }

    public SeatUnavailableException(String message) {
        super(message);
        this.seats = List.of();
    }

    /** The seats that could not be taken; empty when no specific seats were requested. */
    public List<SeatId> seats() {
        return seats;
    }

    private static String join(List<SeatId> seats) {
        StringBuilder text = new StringBuilder();
        for (SeatId seat : seats) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(seat);
        }
        return text.toString();
    }
}
