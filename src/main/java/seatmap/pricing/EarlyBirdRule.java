package seatmap.pricing;

import java.time.Instant;
import java.util.Optional;

import seatmap.error.ValidationException;

/** A percentage off for orders priced strictly before a deadline. */
public record EarlyBirdRule(Percent percent, Instant until) implements PricingRule {

    public EarlyBirdRule {
        if (percent == null || until == null) {
            throw new ValidationException("an early-bird rule needs a percentage and a deadline");
        }
    }

    @Override
    public String describe() {
        return "early-bird " + percent + " until " + until;
    }

    @Override
    public Optional<Discount> evaluate(PricingContext context) {
        if (!context.now().isBefore(until)) {
            return Optional.empty();
        }
        return Optional.of(new Discount("early-bird " + percent, context.subtotal().percent(percent)));
    }
}
