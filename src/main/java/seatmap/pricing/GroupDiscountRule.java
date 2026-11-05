package seatmap.pricing;

import java.util.Optional;

import seatmap.error.ValidationException;

/** A percentage off for orders with at least {@code minSeats} seats. */
public record GroupDiscountRule(Percent percent, int minSeats) implements PricingRule {

    public GroupDiscountRule {
        if (percent == null) {
            throw new ValidationException("a group rule needs a percentage");
        }
        if (minSeats < 2) {
            throw new ValidationException("a group rule needs a minimum of at least 2 seats");
        }
    }

    @Override
    public String describe() {
        return "group " + percent + " from " + minSeats + " seats";
    }

    @Override
    public Optional<Discount> evaluate(PricingContext context) {
        if (context.seats().size() < minSeats) {
            return Optional.empty();
        }
        return Optional.of(new Discount("group " + percent, context.subtotal().percent(percent)));
    }
}
