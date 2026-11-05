package seatmap.pricing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import seatmap.error.ValidationException;
import seatmap.venue.Seat;

/**
 * Prices an order.
 *
 * <p>Automatic rules do not stack: of all rules that apply, the single largest discount is
 * taken (the first declared wins a tie). A promo code is then applied on top of the
 * already reduced amount. The total never drops below zero.
 */
public final class PricingEngine {

    /**
     * @param promo a promo code that the caller has already checked for validity, or
     *              {@code null} when none was given
     */
    public Quote quote(PriceList prices, List<PricingRule> rules, List<Seat> seats, Instant now, PromoCode promo) {
        if (seats == null || seats.isEmpty()) {
            throw new ValidationException("cannot price an order without seats");
        }
        List<Quote.Line> lines = new ArrayList<>();
        Money subtotal = Money.ZERO;
        for (Seat seat : seats) {
            Money price = prices.priceOf(seat);
            lines.add(new Quote.Line(seat.id(), seat.category(), price));
            subtotal = subtotal.plus(price);
        }

        List<Discount> discounts = new ArrayList<>();
        Money due = subtotal;
        Optional<Discount> best = bestRuleDiscount(rules, new PricingContext(now, seats, subtotal));
        if (best.isPresent()) {
            Discount capped = new Discount(best.get().label(), best.get().amount().min(due));
            discounts.add(capped);
            due = due.minusFloored(capped.amount());
        }
        if (promo != null) {
            Money amount = promo.discountOn(due);
            if (!amount.isZero()) {
                discounts.add(new Discount("promo " + promo.code(), amount));
                due = due.minusFloored(amount);
            }
        }
        return new Quote(lines, subtotal, discounts, due);
    }

    private static Optional<Discount> bestRuleDiscount(List<PricingRule> rules, PricingContext context) {
        Discount best = null;
        for (PricingRule rule : rules) {
            Optional<Discount> candidate = rule.evaluate(context);
            if (candidate.isPresent() && !candidate.get().amount().isZero()
                    && (best == null || candidate.get().amount().compareTo(best.amount()) > 0)) {
                best = candidate.get();
            }
        }
        return Optional.ofNullable(best);
    }
}
