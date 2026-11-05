package seatmap.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import seatmap.error.ValidationException;
import seatmap.pricing.Discount;
import seatmap.pricing.Money;
import seatmap.pricing.Quote;
import seatmap.venue.SeatId;

class BookingTest {
    private static final Instant T0 = Instant.parse("2026-05-01T10:00:00Z");

    private static Quote quote() {
        return new Quote(
                List.of(new Quote.Line(SeatId.parse("A-1-1"), "STD", Money.parse("40")),
                        new Quote.Line(SeatId.parse("A-1-2"), "STD", Money.parse("40"))),
                Money.parse("80"), List.of(new Discount("group 10%", Money.parse("8"))), Money.parse("72"));
    }

    @Test
    void holdExpiresExactlyAtItsDeadline() {
        Hold hold = new Hold("H-1", "E", "Ann", List.of(SeatId.parse("A-1-1")), T0, T0.plusSeconds(600));
        assertFalse(hold.isExpired(T0.plusSeconds(599)));
        assertTrue(hold.isExpired(T0.plusSeconds(600)));
        assertThrows(ValidationException.class, () -> new Hold("H-1", "E", "Ann", List.of(), T0, T0));
    }

    @Test
    void confirmedBookingKeepsTheFullTotal() {
        Booking booking = Booking.confirmed("B-1", "E", "Ann", quote(), null, T0);
        assertTrue(booking.isConfirmed());
        assertNull(booking.cancelledAt());
        assertEquals(Money.parse("72"), booking.total());
        assertEquals(Money.parse("72"), booking.retained());
        assertEquals(List.of(SeatId.parse("A-1-1"), SeatId.parse("A-1-2")), booking.seats());
    }

    @Test
    void cancellingProducesANewValueWithTheRefund() {
        Booking booking = Booking.confirmed("B-1", "E", "Ann", quote(), "SPRING", T0);
        Booking cancelled = booking.cancelled(T0.plusSeconds(60), Money.parse("36"));
        assertTrue(booking.isConfirmed());
        assertFalse(cancelled.isConfirmed());
        assertEquals(Money.parse("36"), cancelled.retained());
        assertEquals("SPRING", cancelled.promoCode());
        assertThrows(ValidationException.class, () -> booking.cancelled(T0, Money.parse("72.01")));
        assertThrows(ValidationException.class, () -> booking.cancelled(null, Money.ZERO));
    }
}
