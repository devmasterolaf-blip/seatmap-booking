package seatmap.repo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import seatmap.booking.Booking;
import seatmap.booking.Hold;

/** Keeps everything in maps; contents are lost when the process ends. */
public final class InMemoryReservationRepository implements ReservationRepository {
    private final Map<String, Long> sequences = new HashMap<>();
    private final Map<String, Hold> holds = new LinkedHashMap<>();
    private final Map<String, Booking> bookings = new LinkedHashMap<>();

    @Override
    public synchronized long nextSequence(String name) {
        return sequences.merge(name, 1L, Long::sum);
    }

    @Override
    public synchronized void saveHold(Hold hold) {
        holds.put(hold.id(), hold);
    }

    @Override
    public synchronized Optional<Hold> findHold(String id) {
        return Optional.ofNullable(holds.get(id));
    }

    @Override
    public synchronized boolean deleteHold(String id) {
        return holds.remove(id) != null;
    }

    @Override
    public synchronized List<Hold> holdsForEvent(String eventId) {
        return holds.values().stream().filter(h -> h.eventId().equals(eventId)).toList();
    }

    @Override
    public synchronized void saveBooking(Booking booking) {
        bookings.put(booking.id(), booking);
    }

    @Override
    public synchronized Optional<Booking> findBooking(String id) {
        return Optional.ofNullable(bookings.get(id));
    }

    @Override
    public synchronized List<Booking> bookingsForEvent(String eventId) {
        return bookings.values().stream().filter(b -> b.eventId().equals(eventId)).toList();
    }

    /** Current sequence values, for snapshotting. */
    public synchronized Map<String, Long> sequences() {
        return new HashMap<>(sequences);
    }

    /** Every hold in insertion order, for snapshotting. */
    public synchronized List<Hold> allHolds() {
        return List.copyOf(holds.values());
    }

    /** Every booking in insertion order, for snapshotting. */
    public synchronized List<Booking> allBookings() {
        return List.copyOf(bookings.values());
    }

    /** Raises a sequence to at least {@code value}; used when restoring a snapshot. */
    public synchronized void restoreSequence(String name, long value) {
        sequences.merge(name, value, Math::max);
    }
}
