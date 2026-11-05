package seatmap.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import seatmap.booking.Booking;
import seatmap.event.Event;
import seatmap.pricing.Money;
import seatmap.pricing.Quote;

/**
 * Money taken for an event.
 *
 * @param confirmedBookings  number of live bookings
 * @param cancelledBookings  number of cancelled bookings
 * @param seatsSold          seats in live bookings
 * @param gross              list-price value of the seats in live bookings
 * @param discounts          discounts granted on live bookings
 * @param sales              amount paid for live bookings ({@code gross - discounts})
 * @param refunded           amount returned for cancelled bookings
 * @param cancellationIncome amount kept from cancelled bookings
 * @param net                {@code sales + cancellationIncome}
 * @param categories         seats sold and list-price value per category, live bookings only
 * @param promoUses          live bookings per redeemed promo code
 */
public record RevenueReport(String eventId, int confirmedBookings, int cancelledBookings, int seatsSold,
        Money gross, Money discounts, Money sales, Money refunded, Money cancellationIncome, Money net,
        List<CategorySales> categories, Map<String, Integer> promoUses) {

    /** Seats sold in one category and their value at list price. */
    public record CategorySales(String category, int seats, Money gross) {
    }

    public RevenueReport {
        categories = List.copyOf(categories);
        promoUses = Collections.unmodifiableMap(new TreeMap<>(promoUses));
    }

    public static RevenueReport of(Event event, List<Booking> bookings) {
        int confirmed = 0;
        int cancelled = 0;
        int seats = 0;
        Money gross = Money.ZERO;
        Money discounts = Money.ZERO;
        Money sales = Money.ZERO;
        Money refunded = Money.ZERO;
        Money kept = Money.ZERO;
        Map<String, Integer> seatsByCategory = new LinkedHashMap<>();
        Map<String, Money> grossByCategory = new LinkedHashMap<>();
        for (String category : event.venue().categories().keySet()) {
            seatsByCategory.put(category, 0);
            grossByCategory.put(category, Money.ZERO);
        }
        Map<String, Integer> promoUses = new TreeMap<>();
        for (Booking booking : bookings) {
            if (!booking.isConfirmed()) {
                cancelled++;
                refunded = refunded.plus(booking.refund());
                kept = kept.plus(booking.retained());
                continue;
            }
            confirmed++;
            seats += booking.quote().lines().size();
            gross = gross.plus(booking.quote().subtotal());
            discounts = discounts.plus(booking.quote().discountTotal());
            sales = sales.plus(booking.total());
            for (Quote.Line line : booking.quote().lines()) {
                seatsByCategory.merge(line.category(), 1, Integer::sum);
                grossByCategory.merge(line.category(), line.price(), Money::plus);
            }
            if (booking.promoCode() != null) {
                promoUses.merge(booking.promoCode(), 1, Integer::sum);
            }
        }
        List<CategorySales> categories = new ArrayList<>();
        seatsByCategory.forEach((category, count) ->
                categories.add(new CategorySales(category, count, grossByCategory.get(category))));
        return new RevenueReport(event.id(), confirmed, cancelled, seats, gross, discounts, sales, refunded, kept,
                sales.plus(kept), categories, promoUses);
    }
}
