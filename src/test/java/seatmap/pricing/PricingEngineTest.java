package seatmap.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import seatmap.error.PromoCodeException;
import seatmap.error.ValidationException;
import seatmap.venue.Seat;
import seatmap.venue.SeatId;

class PricingEngineTest {
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-02-01T00:00:00Z");

    private final PricingEngine engine = new PricingEngine();
    private final PriceList prices = prices("PREM", "80.00", "STD", "40.00");

    private static PriceList prices(String... pairs) {
        Map<String, Money> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], Money.parse(pairs[i + 1]));
        }
        return new PriceList(map);
    }

    private static List<Seat> seats(String category, int count) {
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            seats.add(new Seat(new SeatId("S", category, i + 1), category, false, i, 0));
        }
        return seats;
    }

    @Test
    void sumsBasePricesPerSeat() {
        List<Seat> order = new ArrayList<>(seats("PREM", 1));
        order.addAll(seats("STD", 2));
        Quote quote = engine.quote(prices, List.of(), order, NOW, null);
        assertEquals(Money.parse("160.00"), quote.subtotal());
        assertEquals(Money.parse("160.00"), quote.total());
        assertEquals(3, quote.lines().size());
        assertEquals(Money.parse("80.00"), quote.lines().get(0).price());
        assertTrue(quote.discounts().isEmpty());
        assertEquals(Money.ZERO, quote.discountTotal());
    }

    @Test
    void earlyBirdAppliesOnlyBeforeDeadline() {
        PricingRule rule = new EarlyBirdRule(Percent.of(10), LATER);
        assertEquals(Money.parse("72.00"), engine.quote(prices, List.of(rule), seats("STD", 2), NOW, null).total());
        assertEquals(Money.parse("80.00"), engine.quote(prices, List.of(rule), seats("STD", 2), LATER, null).total());
    }

    @Test
    void groupDiscountNeedsMinimumSeats() {
        PricingRule rule = new GroupDiscountRule(Percent.of(25), 4);
        assertEquals(Money.parse("120.00"), engine.quote(prices, List.of(rule), seats("STD", 3), NOW, null).total());
        Quote four = engine.quote(prices, List.of(rule), seats("STD", 4), NOW, null);
        assertEquals(Money.parse("120.00"), four.total());
        assertEquals("group 25%", four.discounts().get(0).label());
        assertThrows(ValidationException.class, () -> new GroupDiscountRule(Percent.of(10), 1));
    }

    @Test
    void onlyTheLargestAutomaticRuleApplies() {
        List<PricingRule> rules = List.of(
                new EarlyBirdRule(Percent.of(10), LATER),
                new GroupDiscountRule(Percent.of(15), 4));
        Quote quote = engine.quote(prices, rules, seats("STD", 4), NOW, null);
        assertEquals(1, quote.discounts().size());
        assertEquals("group 15%", quote.discounts().get(0).label());
        assertEquals(Money.parse("136.00"), quote.total());
        // below the group minimum the early-bird rule is the only one left
        assertEquals("early-bird 10%",
                engine.quote(prices, rules, seats("STD", 2), NOW, null).discounts().get(0).label());
    }

    @Test
    void firstDeclaredRuleWinsATie() {
        List<PricingRule> rules = List.of(
                new GroupDiscountRule(Percent.of(10), 2),
                new EarlyBirdRule(Percent.of(10), LATER));
        assertEquals("group 10%", engine.quote(prices, rules, seats("STD", 2), NOW, null).discounts().get(0).label());
    }

    @Test
    void promoAppliesOnTopOfTheReducedAmount() {
        PromoCode promo = PromoCode.percentOff("spring", Percent.of(50), EARLIER, LATER, PromoCode.UNLIMITED);
        Quote quote = engine.quote(prices, List.of(new EarlyBirdRule(Percent.of(10), LATER)), seats("STD", 2), NOW, promo);
        assertEquals(Money.parse("8.00"), quote.discounts().get(0).amount());
        assertEquals(new Discount("promo SPRING", Money.parse("36.00")), quote.discounts().get(1));
        assertEquals(Money.parse("36.00"), quote.total());
        assertEquals(Money.parse("44.00"), quote.discountTotal());
    }

    @Test
    void fixedPromoNeverPushesTotalBelowZero() {
        PromoCode promo = PromoCode.amountOff("BIG", Money.parse("100"), EARLIER, LATER, PromoCode.UNLIMITED);
        Quote quote = engine.quote(prices, List.of(), seats("STD", 1), NOW, promo);
        assertEquals(Money.ZERO, quote.total());
        assertEquals(Money.parse("40.00"), quote.discounts().get(0).amount());
    }

    @Test
    void promoWindowAndUseLimit() {
        PromoCode promo = PromoCode.percentOff("Vip10", Percent.of(10), EARLIER, LATER, 2);
        assertEquals("VIP10", promo.code());
        promo.checkRedeemable(NOW, 1);
        promo.checkRedeemable(EARLIER, 0);
        assertEquals(PromoCodeException.Reason.EXHAUSTED,
                assertThrows(PromoCodeException.class, () -> promo.checkRedeemable(NOW, 2)).reason());
        assertEquals(PromoCodeException.Reason.EXPIRED,
                assertThrows(PromoCodeException.class, () -> promo.checkRedeemable(LATER, 0)).reason());
        assertEquals(PromoCodeException.Reason.NOT_YET_VALID,
                assertThrows(PromoCodeException.class, () -> promo.checkRedeemable(EARLIER.minusSeconds(1), 0)).reason());
    }

    @Test
    void rejectsInvalidPromoDefinitions() {
        assertThrows(ValidationException.class,
                () -> PromoCode.percentOff("X", Percent.of(10), LATER, EARLIER, 0));
        assertThrows(ValidationException.class,
                () -> PromoCode.percentOff("X", Percent.of(0), EARLIER, LATER, 0));
        assertThrows(ValidationException.class,
                () -> PromoCode.amountOff("X", Money.ZERO, EARLIER, LATER, 0));
        assertThrows(ValidationException.class,
                () -> PromoCode.amountOff("BAD CODE", Money.parse("5"), EARLIER, LATER, 0));
        assertThrows(ValidationException.class,
                () -> PromoCode.amountOff("X", Money.parse("5"), EARLIER, LATER, -1));
    }

    @Test
    void rejectsEmptyOrdersAndUnpricedCategories() {
        assertThrows(ValidationException.class, () -> engine.quote(prices, List.of(), List.of(), NOW, null));
        assertThrows(ValidationException.class, () -> engine.quote(prices, List.of(), seats("BALC", 1), NOW, null));
        assertThrows(ValidationException.class, () -> new PriceList(Map.of()));
    }
}
