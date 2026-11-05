package seatmap.event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import seatmap.error.ConfigParseException;
import seatmap.error.SeatmapException;
import seatmap.error.ValidationException;
import seatmap.pricing.EarlyBirdRule;
import seatmap.pricing.GroupDiscountRule;
import seatmap.pricing.Money;
import seatmap.pricing.Percent;
import seatmap.pricing.PriceList;
import seatmap.pricing.PromoCode;
import seatmap.text.Durations;
import seatmap.text.LineTokenizer;
import seatmap.text.LineTokenizer.Token;
import seatmap.venue.Venue;
import seatmap.venue.VenueLayoutParser;

/**
 * Reads events from the plain-text event format.
 *
 * <pre>
 * event HAMLET "Hamlet"
 *   venue orpheum.layout
 *   starts 2026-12-01T19:30:00Z
 *   hold-ttl 15m
 *   price PREM 85.00
 *   price STD 45.00
 *   rule early-bird 15% until 2026-11-01T00:00:00Z
 *   rule group 10% min 6
 *   promo SPRING10 10% from 2026-09-01T00:00:00Z until 2026-12-01T00:00:00Z max-uses 100
 *   promo FIVEOFF 5.00 from 2026-09-01T00:00:00Z until 2026-12-01T00:00:00Z
 *   refund 100% until 7d
 *   refund 50% until 24h
 * </pre>
 *
 * <p>Times are ISO-8601 instants. A promo value ending in {@code %} is a percentage,
 * anything else a fixed amount.
 */
public final class EventConfigParser {
    private final String source;
    private final Function<String, Venue> venueLoader;
    private final Map<String, Venue> venueCache = new HashMap<>();
    private final List<Event> events = new ArrayList<>();

    private Event.Builder current;
    private String currentId;
    private int currentLine;
    private Map<String, Money> prices;
    private List<RefundPolicy.Tier> refundTiers;
    private boolean venueSet;
    private boolean startSet;
    private boolean holdTtlSet;

    private EventConfigParser(String source, Function<String, Venue> venueLoader) {
        this.source = source;
        this.venueLoader = venueLoader;
    }

    /** Parses an event file, resolving {@code venue} lines relative to the file's directory. */
    public static EventCatalog parse(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SeatmapException("cannot read event file " + file + ": " + e.getMessage(), e);
        }
        Path directory = file.toAbsolutePath().getParent();
        return parse(text, file.getFileName().toString(),
                name -> VenueLayoutParser.parse(directory.resolve(name)));
    }

    /**
     * @param venueLoader turns the argument of a {@code venue} line into a venue; called
     *                    once per distinct name
     */
    public static EventCatalog parse(String text, String source, Function<String, Venue> venueLoader) {
        EventConfigParser parser = new EventConfigParser(source, venueLoader);
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            parser.line(lines[i], i + 1);
        }
        parser.closeEvent();
        try {
            return new EventCatalog(parser.events);
        } catch (ValidationException e) {
            throw new ConfigParseException(source, lines.length, e.getMessage());
        }
    }

    private void line(String raw, int lineNumber) {
        List<Token> tokens = LineTokenizer.tokenize(raw, source, lineNumber);
        if (tokens.isEmpty()) {
            return;
        }
        String directive = tokens.get(0).text();
        if (tokens.get(0).quoted()) {
            throw error(lineNumber, "expected a directive, found quoted text");
        }
        if (directive.equals("event")) {
            event(tokens, lineNumber);
            return;
        }
        if (current == null) {
            throw error(lineNumber, "'" + directive + "' appears before any event");
        }
        try {
            switch (directive) {
                case "venue" -> venue(tokens, lineNumber);
                case "starts" -> starts(tokens, lineNumber);
                case "hold-ttl" -> holdTtl(tokens, lineNumber);
                case "price" -> price(tokens, lineNumber);
                case "rule" -> rule(tokens, lineNumber);
                case "promo" -> promo(tokens, lineNumber);
                case "refund" -> refund(tokens, lineNumber);
                default -> throw error(lineNumber, "unknown directive '" + directive + "'");
            }
        } catch (ValidationException e) {
            throw error(lineNumber, e.getMessage());
        }
    }

    private void event(List<Token> tokens, int lineNumber) {
        if (tokens.size() < 2 || tokens.size() > 3 || tokens.get(1).quoted()) {
            throw error(lineNumber, "expected: event ID [\"NAME\"]");
        }
        closeEvent();
        currentId = tokens.get(1).text();
        currentLine = lineNumber;
        current = Event.builder(currentId);
        if (tokens.size() == 3) {
            current.name(tokens.get(2).text());
        }
        prices = new LinkedHashMap<>();
        refundTiers = new ArrayList<>();
        venueSet = false;
        startSet = false;
        holdTtlSet = false;
    }

    private void venue(List<Token> tokens, int lineNumber) {
        expect(tokens, 2, "venue FILE", lineNumber);
        once(venueSet, "venue", lineNumber);
        venueSet = true;
        String name = tokens.get(1).text();
        Venue venue = venueCache.get(name);
        if (venue == null) {
            venue = venueLoader.apply(name);
            venueCache.put(name, venue);
        }
        current.venue(venue);
    }

    private void starts(List<Token> tokens, int lineNumber) {
        expect(tokens, 2, "starts INSTANT", lineNumber);
        once(startSet, "starts", lineNumber);
        startSet = true;
        current.startsAt(instant(tokens.get(1).text(), lineNumber));
    }

    private void holdTtl(List<Token> tokens, int lineNumber) {
        expect(tokens, 2, "hold-ttl DURATION", lineNumber);
        once(holdTtlSet, "hold-ttl", lineNumber);
        holdTtlSet = true;
        current.holdTtl(Durations.parse(tokens.get(1).text()));
    }

    private void price(List<Token> tokens, int lineNumber) {
        expect(tokens, 3, "price CATEGORY AMOUNT", lineNumber);
        String category = tokens.get(1).text();
        if (prices.put(category, Money.parse(tokens.get(2).text())) != null) {
            throw error(lineNumber, "category " + category + " is priced twice");
        }
    }

    private void rule(List<Token> tokens, int lineNumber) {
        String kind = tokens.size() > 1 ? tokens.get(1).text() : "";
        switch (kind) {
            case "early-bird" -> {
                if (tokens.size() != 5 || !tokens.get(3).text().equals("until")) {
                    throw error(lineNumber, "expected: rule early-bird PERCENT until INSTANT");
                }
                current.rule(new EarlyBirdRule(Percent.parse(tokens.get(2).text()),
                        instant(tokens.get(4).text(), lineNumber)));
            }
            case "group" -> {
                if (tokens.size() != 5 || !tokens.get(3).text().equals("min")) {
                    throw error(lineNumber, "expected: rule group PERCENT min SEATS");
                }
                current.rule(new GroupDiscountRule(Percent.parse(tokens.get(2).text()),
                        integer(tokens.get(4).text(), "group minimum", lineNumber)));
            }
            default -> throw error(lineNumber, "unknown rule '" + kind + "' (use early-bird or group)");
        }
    }

    private void promo(List<Token> tokens, int lineNumber) {
        String usage = "expected: promo CODE VALUE from INSTANT until INSTANT [max-uses N]";
        if ((tokens.size() != 7 && tokens.size() != 9)
                || !tokens.get(3).text().equals("from") || !tokens.get(5).text().equals("until")
                || (tokens.size() == 9 && !tokens.get(7).text().equals("max-uses"))) {
            throw error(lineNumber, usage);
        }
        String code = tokens.get(1).text();
        String value = tokens.get(2).text();
        Instant from = instant(tokens.get(4).text(), lineNumber);
        Instant until = instant(tokens.get(6).text(), lineNumber);
        int maxUses = PromoCode.UNLIMITED;
        if (tokens.size() == 9) {
            maxUses = integer(tokens.get(8).text(), "max-uses", lineNumber);
            if (maxUses < 1) {
                throw error(lineNumber, "max-uses must be at least 1");
            }
        }
        current.promoCode(value.endsWith("%")
                ? PromoCode.percentOff(code, Percent.parse(value), from, until, maxUses)
                : PromoCode.amountOff(code, Money.parse(value), from, until, maxUses));
    }

    private void refund(List<Token> tokens, int lineNumber) {
        if (tokens.size() != 4 || !tokens.get(2).text().equals("until")) {
            throw error(lineNumber, "expected: refund PERCENT until DURATION");
        }
        refundTiers.add(new RefundPolicy.Tier(Percent.parse(tokens.get(1).text()),
                Durations.parse(tokens.get(3).text())));
    }

    private void closeEvent() {
        if (current == null) {
            return;
        }
        try {
            if (prices.isEmpty()) {
                throw new ValidationException("event " + currentId + " has no prices");
            }
            current.prices(new PriceList(prices));
            current.refundPolicy(new RefundPolicy(refundTiers));
            events.add(current.build());
        } catch (ValidationException e) {
            throw error(currentLine, e.getMessage());
        }
        current = null;
    }

    private void expect(List<Token> tokens, int size, String usage, int lineNumber) {
        if (tokens.size() != size) {
            throw error(lineNumber, "expected: " + usage);
        }
    }

    private void once(boolean alreadySet, String directive, int lineNumber) {
        if (alreadySet) {
            throw error(lineNumber, "'" + directive + "' is given twice for event " + currentId);
        }
    }

    private Instant instant(String text, int lineNumber) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw error(lineNumber, "'" + text + "' is not an ISO-8601 instant such as 2026-12-01T19:30:00Z");
        }
    }

    private int integer(String text, String what, int lineNumber) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw error(lineNumber, what + " '" + text + "' is not a whole number");
        }
    }

    private ConfigParseException error(int lineNumber, String message) {
        return new ConfigParseException(source, lineNumber, message);
    }
}
