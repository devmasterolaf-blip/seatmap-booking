package seatmap.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import seatmap.booking.Booking;
import seatmap.booking.Hold;
import seatmap.pricing.Money;
import seatmap.pricing.Quote;
import seatmap.venue.SeatId;

class InMemoryReservationRepositoryTest {
    private static final Instant T0 = Instant.parse("2026-05-01T10:00:00Z");

    static Hold hold(String id, String event, String seat) {
        return new Hold(id, event, "Ann", List.of(SeatId.parse(seat)), T0, T0.plusSeconds(600));
    }

    static Booking booking(String id, String event, String seat) {
        Quote quote = new Quote(List.of(new Quote.Line(SeatId.parse(seat), "STD", Money.parse("40"))),
                Money.parse("40"), List.of(), Money.parse("40"));
        return Booking.confirmed(id, event, "Ann", quote, null, T0);
    }

    @Test
    void sequencesAreIndependentPerName() {
        ReservationRepository repo = new InMemoryReservationRepository();
        assertEquals(1, repo.nextSequence("hold"));
        assertEquals(2, repo.nextSequence("hold"));
        assertEquals(1, repo.nextSequence("booking"));
    }

    @Test
    void storesFindsAndDeletesHolds() {
        ReservationRepository repo = new InMemoryReservationRepository();
        repo.saveHold(hold("H-1", "E1", "A-1-1"));
        repo.saveHold(hold("H-2", "E2", "A-1-1"));
        repo.saveHold(hold("H-3", "E1", "A-1-2"));
        assertEquals(List.of("H-1", "H-3"), repo.holdsForEvent("E1").stream().map(Hold::id).toList());
        assertTrue(repo.findHold("H-2").isPresent());
        assertTrue(repo.deleteHold("H-2"));
        assertFalse(repo.deleteHold("H-2"));
        assertTrue(repo.findHold("H-2").isEmpty());
        assertTrue(repo.holdsForEvent("E2").isEmpty());
    }

    @Test
    void savingABookingAgainReplacesIt() {
        ReservationRepository repo = new InMemoryReservationRepository();
        Booking booking = booking("B-1", "E1", "A-1-1");
        repo.saveBooking(booking);
        repo.saveBooking(booking.cancelled(T0.plusSeconds(5), Money.parse("10")));
        assertEquals(1, repo.bookingsForEvent("E1").size());
        assertFalse(repo.findBooking("B-1").orElseThrow().isConfirmed());
        assertTrue(repo.findBooking("B-9").isEmpty());
    }
}
