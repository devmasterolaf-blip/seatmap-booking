package seatmap.event;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import seatmap.error.ValidationException;
import seatmap.pricing.Money;
import seatmap.pricing.Percent;
import seatmap.text.Durations;

/**
 * How much of the paid amount is returned when a booking is cancelled.
 *
 * <p>The policy is a list of tiers. A tier "P% until D" grants P% when the cancellation
 * happens at least D before the event starts. The most generous tier whose lead time is
 * met applies; when none is met nothing is refunded.
 */
public final class RefundPolicy {

    /** One step of the policy. */
    public record Tier(Percent percent, Duration leadTime) {
        public Tier {
            if (percent == null || leadTime == null) {
                throw new ValidationException("a refund tier needs a percentage and a lead time");
            }
            if (leadTime.isNegative()) {
                throw new ValidationException("a refund lead time must not be negative");
            }
        }
    }

    private static final RefundPolicy NONE = new RefundPolicy(List.of());

    private final List<Tier> tiers;

    public RefundPolicy(List<Tier> tiers) {
        List<Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparing(Tier::leadTime).reversed());
        for (int i = 1; i < sorted.size(); i++) {
            Tier longer = sorted.get(i - 1);
            Tier shorter = sorted.get(i);
            if (longer.leadTime().equals(shorter.leadTime())) {
                throw new ValidationException("two refund tiers share the lead time "
                        + Durations.format(shorter.leadTime()));
            }
            if (shorter.percent().basisPoints() > longer.percent().basisPoints()) {
                throw new ValidationException("refund tiers must not grow as the event gets closer: "
                        + shorter.percent() + " until " + Durations.format(shorter.leadTime())
                        + " exceeds " + longer.percent() + " until " + Durations.format(longer.leadTime()));
            }
        }
        this.tiers = List.copyOf(sorted);
    }

    /** A policy that never refunds anything. */
    public static RefundPolicy none() {
        return NONE;
    }

    /** Tiers ordered from the longest lead time to the shortest. */
    public List<Tier> tiers() {
        return tiers;
    }

    public Percent refundPercent(Instant now, Instant eventStart) {
        Duration lead = Duration.between(now, eventStart);
        for (Tier tier : tiers) {
            if (lead.compareTo(tier.leadTime()) >= 0) {
                return tier.percent();
            }
        }
        return Percent.of(0);
    }

    public Money refundFor(Money paid, Instant now, Instant eventStart) {
        return paid.percent(refundPercent(now, eventStart));
    }
}
