package seatmap.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import seatmap.error.ConfigParseException;
import seatmap.error.NotFoundException;
import seatmap.pricing.Money;
import seatmap.pricing.Percent;
import seatmap.venue.Venue;
import seatmap.venue.VenueLayoutParser;

class EventConfigParserTest {
    private static final String LAYOUT =
            "venue \"Hall\"\ncategory PREM\ncategory STD\nsection A\n row 1 1-4:PREM\n row 2 1-4:STD\n";
    private static final Venue VENUE = VenueLayoutParser.parse(LAYOUT, "hall.layout");

    private static final String CONFIG = """
            # two nights
            event HAMLET "Hamlet"
              venue hall.layout
              starts 2026-12-01T19:30:00Z
              hold-ttl 15m
              price PREM 85.00
              price STD 45
              rule early-bird 15% until 2026-11-01T00:00:00Z
              rule group 10% min 6
              promo spring10 10% from 2026-09-01T00:00:00Z until 2026-12-01T00:00:00Z max-uses 100
              promo FIVEOFF 5.00 from 2026-09-01T00:00:00Z until 2026-12-01T00:00:00Z
              refund 50% until 24h
              refund 100% until 7d

            event MACBETH
              venue hall.layout
              starts 2026-12-02T19:30:00Z
              price PREM 70
              price STD 35
            """;

    private static EventCatalog parse(String text) {
        return EventConfigParser.parse(text, "events.conf", name -> VENUE);
    }

    private static ConfigParseException fails(String text) {
        return assertThrows(ConfigParseException.class, () -> parse(text));
    }

    private static String minimal(String extra) {
        return "event E\n venue hall.layout\n starts 2026-12-01T19:30:00Z\n price PREM 1\n price STD 1\n" + extra;
    }

    @Test
    void parsesAFullEvent() {
        EventCatalog catalog = parse(CONFIG);
        assertEquals(2, catalog.all().size());
        Event hamlet = catalog.require("HAMLET");
        assertEquals("Hamlet", hamlet.name());
        assertEquals(Instant.parse("2026-12-01T19:30:00Z"), hamlet.startsAt());
        assertEquals(Duration.ofMinutes(15), hamlet.holdTtl());
        assertEquals(Money.parse("45.00"), hamlet.prices().priceOf("STD"));
        assertEquals(2, hamlet.rules().size());
        assertEquals("group 10% from 6 seats", hamlet.rules().get(1).describe());
        assertEquals(100, hamlet.promoCode("SPRING10").orElseThrow().maxUses());
        assertTrue(hamlet.promoCode("SPRING10").orElseThrow().isPercentage());
        assertEquals(Money.parse("5.00"), hamlet.promoCode("fiveoff").orElseThrow().discountOn(Money.parse("20")));
        assertEquals(Percent.of(100), hamlet.refundPolicy().tiers().get(0).percent());
    }

    @Test
    void appliesDefaultsAndUsesIdAsName() {
        Event macbeth = parse(CONFIG).require("MACBETH");
        assertEquals("MACBETH", macbeth.name());
        assertEquals(Event.DEFAULT_HOLD_TTL, macbeth.holdTtl());
        assertTrue(macbeth.promoCodes().isEmpty());
    }

    @Test
    void catalogLookups() {
        EventCatalog catalog = parse(CONFIG);
        assertTrue(catalog.find("MACBETH").isPresent());
        NotFoundException e = assertThrows(NotFoundException.class, () -> catalog.require("OTHELLO"));
        assertEquals("event", e.kind());
        assertEquals("OTHELLO", e.id());
        assertTrue(parse("# nothing here\n").all().isEmpty());
    }

    @Test
    void loadsEachVenueFileOnce() {
        List<String> loaded = new ArrayList<>();
        EventConfigParser.parse(CONFIG, "events.conf", name -> {
            loaded.add(name);
            return VENUE;
        });
        assertEquals(List.of("hall.layout"), loaded);
    }

    @Test
    void reportsTheLineOfBadDirectives() {
        assertEquals(1, fails("price STD 5\n").line());
        assertEquals(6, fails(minimal("colour blue\n")).line());
        assertEquals(6, fails(minimal("starts 2026-12-02T19:30:00Z\n")).line());
        assertEquals(6, fails(minimal("price STD 2\n")).line());
        assertEquals(6, fails(minimal("hold-ttl soon\n")).line());
        assertEquals(6, fails(minimal("rule happy-hour 5% until 2026-01-01T00:00:00Z\n")).line());
        assertEquals(6, fails(minimal("rule group 5% min six\n")).line());
        assertEquals(6, fails(minimal("rule early-bird 5% until tomorrow\n")).line());
        assertEquals(6, fails(minimal("promo X 5% from 2026-01-01T00:00:00Z\n")).line());
        assertEquals(6, fails(minimal("promo X 5% from 2026-01-01T00:00:00Z until 2026-02-01T00:00:00Z max-uses 0\n")).line());
        assertEquals(6, fails(minimal("refund 50% before 24h\n")).line());
    }

    @Test
    void reportsEventLevelProblemsOnTheEventLine() {
        ConfigParseException missingPrice = fails("event E\n venue hall.layout\n starts 2026-12-01T19:30:00Z\n price PREM 1\n");
        assertEquals(1, missingPrice.line());
        assertTrue(missingPrice.getMessage().contains("no price for category STD"));
        assertTrue(fails("event E\n venue hall.layout\n price PREM 1\n price STD 1\n").getMessage().contains("no start time"));
        assertTrue(fails("event E\n starts 2026-12-01T19:30:00Z\n price PREM 1\n price STD 1\n").getMessage().contains("no venue"));
        assertTrue(fails(minimal("refund 50% until 7d\nrefund 80% until 1d\n")).getMessage().contains("must not grow"));
        assertTrue(fails(minimal("") + minimal("")).getMessage().contains("declared twice"));
    }

    @Test
    void resolvesVenueFilesNextToTheEventFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("hall.layout"), LAYOUT);
        Files.writeString(dir.resolve("events.conf"), minimal(""));
        Event event = EventConfigParser.parse(dir.resolve("events.conf")).require("E");
        assertEquals("Hall", event.venue().name());
        assertEquals(8, event.venue().capacity());
    }
}
