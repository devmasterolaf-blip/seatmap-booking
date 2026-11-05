package seatmap.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import seatmap.error.ValidationException;
import seatmap.pricing.Money;
import seatmap.pricing.Percent;
import seatmap.pricing.PriceList;
import seatmap.pricing.PromoCode;
import seatmap.text.Durations;
import seatmap.venue.Venue;
import seatmap.venue.VenueLayoutParser;

class EventTest {
    private static final Instant START = Instant.parse("2026-12-01T19:30:00Z");
    private static final Venue VENUE = VenueLayoutParser.parse(
            "venue \"Hall\"\ncategory PREM\ncategory STD\nsection A\n row 1 1-4:PREM\n row 2 1-4:STD\n", "hall");

    private static Event.Builder valid() {
        return Event.builder("HAMLET").name("Hamlet").startsAt(START).venue(VENUE)
                .prices(new PriceList(Map.of("PREM", Money.parse("80"), "STD", Money.parse("40"))));
    }

    @Test
    void buildsWithDefaults() {
        Event event = valid().build();
        assertEquals("HAMLET", event.id());
        assertEquals(Event.DEFAULT_HOLD_TTL, event.holdTtl());
        assertTrue(event.rules().isEmpty());
        assertTrue(event.refundPolicy().tiers().isEmpty());
        assertFalse(event.hasStarted(START.minusSeconds(1)));
        assertTrue(event.hasStarted(START));
    }

    @Test
    void requiresAPriceForEveryVenueCategoryAndNoOthers() {
        assertThrows(ValidationException.class,
                () -> valid().prices(new PriceList(Map.of("PREM", Money.parse("80")))).build());
        assertThrows(ValidationException.class, () -> valid().prices(new PriceList(
                Map.of("PREM", Money.parse("80"), "STD", Money.parse("40"), "BALC", Money.parse("10")))).build());
    }

    @Test
    void rejectsMissingPartsAndBadHoldTime() {
        assertThrows(ValidationException.class, () -> valid().startsAt(null).build());
        assertThrows(ValidationException.class, () -> valid().venue(null).build());
        assertThrows(ValidationException.class, () -> valid().holdTtl(Duration.ZERO).build());
        assertThrows(ValidationException.class, () -> Event.builder("bad id").build());
    }

    @Test
    void promoLookupIgnoresCaseAndRejectsDuplicates() {
        PromoCode promo = PromoCode.percentOff("Spring", Percent.of(10), START.minusSeconds(100), START, 0);
        Event event = valid().promoCode(promo).build();
        assertTrue(event.promoCode("spring").isPresent());
        assertTrue(event.promoCode("SUMMER").isEmpty());
        assertThrows(ValidationException.class, () -> valid().promoCode(promo).promoCode(promo).build());
    }

    @Test
    void refundPolicyPicksTheMostGenerousTierThatIsMet() {
        RefundPolicy policy = new RefundPolicy(List.of(
                new RefundPolicy.Tier(Percent.of(50), Duration.ofHours(24)),
                new RefundPolicy.Tier(Percent.of(100), Duration.ofDays(7))));
        assertEquals(Duration.ofDays(7), policy.tiers().get(0).leadTime());
        assertEquals(Percent.of(100), policy.refundPercent(START.minus(Duration.ofDays(7)), START));
        assertEquals(Percent.of(50), policy.refundPercent(START.minus(Duration.ofDays(7)).plusSeconds(1), START));
        assertEquals(Percent.of(50), policy.refundPercent(START.minus(Duration.ofHours(24)), START));
        assertEquals(Percent.of(0), policy.refundPercent(START.minus(Duration.ofHours(23)), START));
        assertEquals(Money.parse("22.50"), policy.refundFor(Money.parse("45"), START.minus(Duration.ofDays(2)), START));
        assertEquals(Money.ZERO, RefundPolicy.none().refundFor(Money.parse("45"), START.minus(Duration.ofDays(90)), START));
    }

    @Test
    void refundPolicyRejectsInconsistentTiers() {
        assertThrows(ValidationException.class, () -> new RefundPolicy(List.of(
                new RefundPolicy.Tier(Percent.of(50), Duration.ofDays(7)),
                new RefundPolicy.Tier(Percent.of(80), Duration.ofDays(1)))));
        assertThrows(ValidationException.class, () -> new RefundPolicy(List.of(
                new RefundPolicy.Tier(Percent.of(50), Duration.ofDays(1)),
                new RefundPolicy.Tier(Percent.of(50), Duration.ofHours(24)))));
        assertThrows(ValidationException.class,
                () -> new RefundPolicy.Tier(Percent.of(50), Duration.ofDays(-1)));
    }

    @Test
    void durationsParseAndFormat() {
        assertEquals(Duration.ofMinutes(15), Durations.parse("15m"));
        assertEquals(Duration.ofDays(7), Durations.parse("7d"));
        assertEquals(Duration.ofSeconds(90), Durations.parse("90s"));
        assertEquals("36h", Durations.format(Duration.ofHours(36)));
        assertEquals("2d", Durations.format(Duration.ofHours(48)));
        assertEquals("90s", Durations.format(Duration.ofSeconds(90)));
        assertEquals("0s", Durations.format(Duration.ZERO));
        for (String bad : new String[] {"", "m", "15", "15w", "-5m", "+5m", "1.5h"}) {
            assertThrows(ValidationException.class, () -> Durations.parse(bad), bad);
        }
    }
}
