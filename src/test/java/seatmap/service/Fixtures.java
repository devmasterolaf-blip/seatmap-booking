package seatmap.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import seatmap.event.EventCatalog;
import seatmap.event.EventConfigParser;
import seatmap.venue.SeatId;
import seatmap.venue.Venue;
import seatmap.venue.VenueLayoutParser;

/** A small venue and two events shared by the service tests. */
public final class Fixtures {
    public static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    public static final Instant HAMLET_START = Instant.parse("2026-12-01T19:30:00Z");

    public static final String LAYOUT = """
            venue "Orpheum"
            category PREM "Premium"
            category STD "Standard"
            section ORCH "Orchestra"
              row A 1-3:STD | 4-9:PREM | 10-12:STD
              row B 1-3:STD | 4-9:PREM | 10-12:STD
              row C 1-2:STD+acc 3:STD | 4-9:STD | 10:STD 11-12:STD+acc
            section BALC "Balcony"
              row A 1-8:STD
              row B 1-8:STD
            """;

    public static final String EVENTS = """
            event HAMLET "Hamlet"
              venue orpheum.layout
              starts 2026-12-01T19:30:00Z
              hold-ttl 10m
              price PREM 80.00
              price STD 40.00
              rule early-bird 10% until 2026-11-01T00:00:00Z
              rule group 20% min 6
              promo SPRING 25% from 2026-09-01T00:00:00Z until 2026-11-15T00:00:00Z max-uses 2
              promo FIVER 5.00 from 2026-09-01T00:00:00Z until 2026-12-01T00:00:00Z
              refund 100% until 7d
              refund 50% until 24h
            event MACBETH "Macbeth"
              venue orpheum.layout
              starts 2026-12-02T19:30:00Z
              price PREM 60.00
              price STD 30.00
            """;

    private Fixtures() {
    }

    public static Venue venue() {
        return VenueLayoutParser.parse(LAYOUT, "orpheum.layout");
    }

    public static EventCatalog catalog() {
        Venue venue = venue();
        return EventConfigParser.parse(EVENTS, "events.conf", name -> venue);
    }

    public static List<SeatId> seats(String... ids) {
        return Arrays.stream(ids).map(SeatId::parse).toList();
    }
}
