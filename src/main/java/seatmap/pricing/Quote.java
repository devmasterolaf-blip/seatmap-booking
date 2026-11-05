package seatmap.pricing;

import java.util.List;

import seatmap.venue.SeatId;

/**
 * A priced order: one line per seat, the discounts that were applied and the amount due.
 */
public record Quote(List<Line> lines, Money subtotal, List<Discount> discounts, Money total) {

    /** Base price of one seat. */
    public record Line(SeatId seat, String category, Money price) {
    }

    public Quote {
        lines = List.copyOf(lines);
        discounts = List.copyOf(discounts);
    }

    public Money discountTotal() {
        Money sum = Money.ZERO;
        for (Discount discount : discounts) {
            sum = sum.plus(discount.amount());
        }
        return sum;
    }
}
