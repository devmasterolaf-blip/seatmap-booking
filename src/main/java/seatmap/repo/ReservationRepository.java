package seatmap.repo;

import java.util.List;
import java.util.Optional;

import seatmap.booking.Booking;
import seatmap.booking.Hold;

/**
 * Storage for holds and bookings.
 *
 * <p>Implementations must be safe to call from several threads, but they do not have to
 * make compound operations atomic; the reservation service serialises those per event.
 */
public interface ReservationRepository {

    /** Returns 1, 2, 3, ... independently for each {@code name}; never repeats a value. */
    long nextSequence(String name);

    /** Inserts the hold or replaces the one with the same id. */
    void saveHold(Hold hold);

    Optional<Hold> findHold(String id);

    /** @return whether a hold with that id existed */
    boolean deleteHold(String id);

    /** Holds of the event in the order they were first saved, expired ones included. */
    List<Hold> holdsForEvent(String eventId);

    /** Inserts the booking or replaces the one with the same id. */
    void saveBooking(Booking booking);

    Optional<Booking> findBooking(String id);

    /** Bookings of the event in the order they were first saved, cancelled ones included. */
    List<Booking> bookingsForEvent(String eventId);
}
