package seatmap.event;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import seatmap.error.ValidationException;
import seatmap.pricing.PriceList;
import seatmap.pricing.PricingRule;
import seatmap.pricing.PromoCode;
import seatmap.venue.Identifiers;
import seatmap.venue.Venue;

/** A performance at a venue together with everything needed to sell it. */
public final class Event {
    /** How long a hold lasts when the event does not say otherwise. */
    public static final Duration DEFAULT_HOLD_TTL = Duration.ofMinutes(10);

    private final String id;
    private final String name;
    private final Instant startsAt;
    private final Venue venue;
    private final PriceList prices;
    private final List<PricingRule> rules;
    private final Map<String, PromoCode> promoCodes;
    private final RefundPolicy refundPolicy;
    private final Duration holdTtl;

    private Event(Builder builder) {
        this.id = Identifiers.requireCode(builder.id, "event id");
        this.name = (builder.name == null || builder.name.isBlank()) ? builder.id : builder.name;
        if (builder.startsAt == null) {
            throw new ValidationException("event " + id + " has no start time");
        }
        if (builder.venue == null) {
            throw new ValidationException("event " + id + " has no venue");
        }
        if (builder.prices == null) {
            throw new ValidationException("event " + id + " has no prices");
        }
        for (String category : builder.venue.categories().keySet()) {
            if (!builder.prices.covers(category)) {
                throw new ValidationException("event " + id + " has no price for category " + category);
            }
        }
        for (String category : builder.prices.asMap().keySet()) {
            if (!builder.venue.categories().containsKey(category)) {
                throw new ValidationException("event " + id + " prices category " + category
                        + ", which the venue does not have");
            }
        }
        if (builder.holdTtl.isZero() || builder.holdTtl.isNegative()) {
            throw new ValidationException("event " + id + " needs a positive hold time");
        }
        Map<String, PromoCode> promos = new LinkedHashMap<>();
        for (PromoCode promo : builder.promoCodes) {
            if (promos.put(promo.code(), promo) != null) {
                throw new ValidationException("event " + id + " declares promo code " + promo.code() + " twice");
            }
        }
        this.startsAt = builder.startsAt;
        this.venue = builder.venue;
        this.prices = builder.prices;
        this.rules = List.copyOf(builder.rules);
        this.promoCodes = Collections.unmodifiableMap(promos);
        this.refundPolicy = builder.refundPolicy;
        this.holdTtl = builder.holdTtl;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Venue venue() {
        return venue;
    }

    public PriceList prices() {
        return prices;
    }

    public List<PricingRule> rules() {
        return rules;
    }

    public Collection<PromoCode> promoCodes() {
        return promoCodes.values();
    }

    /** Looks a promo code up, ignoring case. */
    public Optional<PromoCode> promoCode(String code) {
        return Optional.ofNullable(promoCodes.get(PromoCode.normalize(code)));
    }

    public RefundPolicy refundPolicy() {
        return refundPolicy;
    }

    public Duration holdTtl() {
        return holdTtl;
    }

    /** Sales stop the moment the event starts. */
    public boolean hasStarted(Instant now) {
        return !now.isBefore(startsAt);
    }

    /** Collects the parts of an {@link Event}. */
    public static final class Builder {
        private final String id;
        private String name;
        private Instant startsAt;
        private Venue venue;
        private PriceList prices;
        private final List<PricingRule> rules = new java.util.ArrayList<>();
        private final List<PromoCode> promoCodes = new java.util.ArrayList<>();
        private RefundPolicy refundPolicy = RefundPolicy.none();
        private Duration holdTtl = DEFAULT_HOLD_TTL;

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder startsAt(Instant startsAt) {
            this.startsAt = startsAt;
            return this;
        }

        public Builder venue(Venue venue) {
            this.venue = venue;
            return this;
        }

        public Builder prices(PriceList prices) {
            this.prices = prices;
            return this;
        }

        public Builder rule(PricingRule rule) {
            this.rules.add(rule);
            return this;
        }

        public Builder promoCode(PromoCode promo) {
            this.promoCodes.add(promo);
            return this;
        }

        public Builder refundPolicy(RefundPolicy policy) {
            this.refundPolicy = policy;
            return this;
        }

        public Builder holdTtl(Duration holdTtl) {
            this.holdTtl = holdTtl;
            return this;
        }

        public Event build() {
            return new Event(this);
        }
    }
}
