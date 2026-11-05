package seatmap.booking;

import java.time.Instant;
import java.util.List;

import seatmap.error.ValidationException;
import seatmap.pricing.Money;
import seatmap.pricing.Quote;
import seatmap.venue.SeatId;

/**
 * A confirmed purchase. Bookings are immutable; cancelling one yields a new value with
 * the status, cancellation time and refund filled in.
 *
 * @param promoCode   the redeemed promo code, or {@code null}
 * @param cancelledAt when the booking was cancelled, or {@code null} while confirmed
 * @param refund      amount returned on cancellation; zero while confirmed
 */
public record Booking(String id, String eventId, String customer, Quote quote, String promoCode,
        Instant confirmedAt, Status status, Instant cancelledAt, Money refund) {

    /** Lifecycle of a booking. */
    public enum Status { CONFIRMED, CANCELLED }

    public Booking {
        if (id == null || eventId == null || customer == null || quote == null || confirmedAt == null
                || status == null || refund == null) {
            throw new ValidationException("a booking needs an id, event, customer, quote, time and status");
        }
        if (quote.lines().isEmpty()) {
            throw new ValidationException("a booking needs at least one seat");
        }
        if ((status == Status.CANCELLED) != (cancelledAt != null)) {
            throw new ValidationException("only a cancelled booking has a cancellation time");
        }
        if (refund.compareTo(quote.total()) > 0) {
            throw new ValidationException("a refund cannot exceed the amount paid");
        }
    }

    public static Booking confirmed(String id, String eventId, String customer, Quote quote, String promoCode,
            Instant confirmedAt) {
        return new Booking(id, eventId, customer, quote, promoCode, confirmedAt, Status.CONFIRMED, null, Money.ZERO);
    }

    public Booking cancelled(Instant at, Money refundAmount) {
        return new Booking(id, eventId, customer, quote, promoCode, confirmedAt, Status.CANCELLED, at, refundAmount);
    }

    public boolean isConfirmed() {
        return status == Status.CONFIRMED;
    }

    public List<SeatId> seats() {
        return quote.lines().stream().map(Quote.Line::seat).toList();
    }

    public Money total() {
        return quote.total();
    }

    /** What the venue keeps: the full total while confirmed, total minus refund after cancellation. */
    public Money retained() {
        return quote.total().minusFloored(refund);
    }
}
