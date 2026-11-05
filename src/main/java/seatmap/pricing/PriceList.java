package seatmap.pricing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import seatmap.error.ValidationException;
import seatmap.venue.Identifiers;
import seatmap.venue.Seat;

/** Base ticket price for each seat category of an event. */
public final class PriceList {
    private final Map<String, Money> prices;

    public PriceList(Map<String, Money> prices) {
        if (prices == null || prices.isEmpty()) {
            throw new ValidationException("a price list needs at least one category price");
        }
        Map<String, Money> copy = new LinkedHashMap<>();
        prices.forEach((category, price) -> {
            Identifiers.requireCode(category, "category code");
            if (price == null) {
                throw new ValidationException("price for category " + category + " is missing");
            }
            copy.put(category, price);
        });
        this.prices = Collections.unmodifiableMap(copy);
    }

    public boolean covers(String category) {
        return prices.containsKey(category);
    }

    public Money priceOf(String category) {
        Money price = prices.get(category);
        if (price == null) {
            throw new ValidationException("no price is set for category " + category);
        }
        return price;
    }

    public Money priceOf(Seat seat) {
        return priceOf(seat.category());
    }

    public Map<String, Money> asMap() {
        return prices;
    }
}
