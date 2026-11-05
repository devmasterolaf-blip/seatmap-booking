package seatmap.pricing;

import java.time.Instant;
import java.util.List;

import seatmap.venue.Seat;

/** What a pricing rule can look at when deciding whether it applies. */
public record PricingContext(Instant now, List<Seat> seats, Money subtotal) {

    public PricingContext {
        seats = List.copyOf(seats);
    }
}
