package seatmap.pricing;

import java.util.Optional;

/** An automatic discount that an event grants without a promo code. */
public interface PricingRule {

    /** Short human-readable description, used in listings. */
    String describe();

    /** The discount this rule grants for the given order, if it applies. */
    Optional<Discount> evaluate(PricingContext context);
}
