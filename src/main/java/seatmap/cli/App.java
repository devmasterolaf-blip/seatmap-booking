package seatmap.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import seatmap.alloc.SeatRequest;
import seatmap.audit.AuditEntry;
import seatmap.audit.FileAuditLog;
import seatmap.booking.Booking;
import seatmap.booking.Hold;
import seatmap.booking.SeatStatus;
import seatmap.error.NotFoundException;
import seatmap.error.SeatmapException;
import seatmap.error.ValidationException;
import seatmap.event.Event;
import seatmap.event.EventCatalog;
import seatmap.event.EventConfigParser;
import seatmap.pricing.Discount;
import seatmap.pricing.Quote;
import seatmap.repo.FileReservationRepository;
import seatmap.report.OccupancyReport;
import seatmap.report.RevenueReport;
import seatmap.service.ReservationService;
import seatmap.text.Durations;
import seatmap.venue.SeatId;
import seatmap.venue.Section;

/**
 * The command-line front end.
 *
 * <p>A data directory holds {@code events.conf} (with the layout files it refers to),
 * the reservation state in {@code state.tsv} and the audit trail in {@code audit.log}.
 * Every invocation loads that directory, runs one command and exits with 0 on success,
 * 1 when the command was understood but failed, 2 for a malformed command line and 3
 * for an unexpected internal error.
 */
public final class App {
    static final String EVENTS_FILE = "events.conf";
    static final String STATE_FILE = "state.tsv";
    static final String AUDIT_FILE = "audit.log";

    private static final Set<String> VALUE_OPTIONS = Set.of("data", "now", "section", "seats", "promo", "customer",
            "best", "category", "accessible", "ttl", "event", "limit");
    private static final Set<String> FLAG_OPTIONS = Set.of("map", "split", "section-only", "help");
    private static final Set<String> GLOBAL = Set.of("data", "now", "help");

    static final String USAGE = """
            usage: seatmap [--data DIR] [--now INSTANT] COMMAND [ARGS]

            commands:
              events                               list the events on sale
              availability EVENT [--section S] [--map]
                                                   free, held and booked seats
              quote EVENT --seats IDS [--promo CODE]
                                                   price seats without reserving them
              hold EVENT --customer NAME --seats IDS [--ttl DURATION]
                                                   hold specific seats (IDS: ORCH-A-1,ORCH-A-2)
              hold EVENT --customer NAME --best N [--category C] [--section S [--section-only]]
                         [--accessible K] [--split] [--ttl DURATION]
                                                   hold the best N seats that are free
              confirm HOLD [--promo CODE]          turn a hold into a booking
              release HOLD                         give up a hold
              cancel BOOKING                       cancel a booking and compute the refund
              report EVENT                         occupancy and revenue
              audit [--event EVENT] [--limit N]    show the audit trail
              expire                               remove holds whose time has run out
              help                                 show this text

            options:
              --data DIR      data directory holding events.conf (default: current directory)
              --now INSTANT   pretend the current time is INSTANT, e.g. 2026-10-01T12:00:00Z

            exit status: 0 success, 1 the command failed, 2 usage error, 3 internal error
            """;

    private final PrintStream out;
    private final PrintStream err;
    private final Clock systemClock;

    public App(PrintStream out, PrintStream err, Clock clock) {
        this.out = out;
        this.err = err;
        this.systemClock = clock;
    }

    /** Runs one command and returns the process exit code. */
    public int run(String... argv) {
        try {
            Args args = Args.parse(Arrays.asList(argv), VALUE_OPTIONS, FLAG_OPTIONS);
            if (args.flag("help") || (!args.positionals().isEmpty() && args.positionals().get(0).equals("help"))) {
                out.print(USAGE);
                return 0;
            }
            if (args.positionals().isEmpty()) {
                throw new UsageException("no command given");
            }
            dispatch(args);
            return 0;
        } catch (UsageException e) {
            err.println("seatmap: " + e.getMessage());
            err.println("Run 'seatmap help' for usage.");
            return 2;
        } catch (SeatmapException e) {
            err.println("seatmap: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            // A defect rather than bad input: say so, without burying the user in a stack trace.
            err.println("seatmap: internal error: " + e);
            return 3;
        }
    }

    private void dispatch(Args args) {
        String command = args.positionals().get(0);
        List<String> operands = args.positionals().subList(1, args.positionals().size());
        Session session = new Session(args);
        switch (command) {
            case "events" -> events(session, args, operands);
            case "availability" -> availability(session, args, operands);
            case "quote" -> quote(session, args, operands);
            case "hold" -> hold(session, args, operands);
            case "confirm" -> confirm(session, args, operands);
            case "release" -> release(session, args, operands);
            case "cancel" -> cancel(session, args, operands);
            case "report" -> report(session, args, operands);
            case "audit" -> audit(session, args, operands);
            case "expire" -> expire(session, args, operands);
            default -> throw new UsageException("unknown command '" + command + "'");
        }
    }

    /** Everything a command needs, loaded lazily from the data directory. */
    private final class Session {
        private final Path dataDir;
        private final Clock clock;
        private EventCatalog catalog;
        private ReservationService service;
        private FileAuditLog auditLog;

        Session(Args args) {
            this.dataDir = dataDirectory(args.value("data").orElse("."));
            this.clock = args.value("now").map(App::fixedClock).orElse(systemClock);
        }

        EventCatalog catalog() {
            if (catalog == null) {
                if (!Files.isDirectory(dataDir)) {
                    throw new SeatmapException("data directory " + dataDir + " does not exist");
                }
                Path events = dataDir.resolve(EVENTS_FILE);
                if (!Files.isRegularFile(events)) {
                    throw new SeatmapException("no " + EVENTS_FILE + " in data directory " + dataDir);
                }
                catalog = EventConfigParser.parse(events);
            }
            return catalog;
        }

        FileAuditLog auditLog() {
            if (auditLog == null) {
                auditLog = new FileAuditLog(dataDir.resolve(AUDIT_FILE));
            }
            return auditLog;
        }

        ReservationService service() {
            if (service == null) {
                service = new ReservationService(catalog(),
                        new FileReservationRepository(dataDir.resolve(STATE_FILE)), clock, auditLog());
            }
            return service;
        }
    }

    private void events(Session session, Args args, List<String> operands) {
        check(args, "events", operands, 0, Set.of());
        TextTable table = new TextTable("llllr");
        for (Event event : session.catalog().all()) {
            table.row(event.id(), event.name(), event.startsAt(), event.venue().name(),
                    event.venue().capacity() + " seats");
        }
        out.print(table.render(""));
    }

    private void availability(Session session, Args args, List<String> operands) {
        check(args, "availability", operands, 1, Set.of("section", "map"));
        Event event = session.catalog().require(operands.get(0));
        List<Section> sections = event.venue().sections();
        if (args.value("section").isPresent()) {
            String code = args.value("section").get();
            sections = List.of(event.venue().section(code)
                    .orElseThrow(() -> new NotFoundException("section", code)));
        }
        Map<SeatId, SeatStatus> statuses = session.service().availability(event.id());
        OccupancyReport report = OccupancyReport.of(event, statuses);
        out.println(describe(event));
        TextTable table = new TextTable("llrrrr").row("Section", "Name", "Capacity", "Free", "Held", "Booked");
        for (OccupancyReport.Group group : report.sections()) {
            if (sections.stream().anyMatch(section -> section.code().equals(group.code()))) {
                table.row(group.code(), group.name(), group.capacity(), group.free(), group.held(), group.booked());
            }
        }
        out.print(table.render(""));
        if (args.flag("map")) {
            for (Section section : sections) {
                out.println();
                out.println(section.code() + "  " + section.name());
                out.print(SeatMapRenderer.render(section, statuses));
            }
            out.println();
            out.println(SeatMapRenderer.LEGEND);
        }
    }

    private void quote(Session session, Args args, List<String> operands) {
        check(args, "quote", operands, 1, Set.of("seats", "promo"));
        List<SeatId> seats = seatList(required(args, "seats", "quote"));
        Quote quote = session.service().quote(operands.get(0), seats, args.value("promo").orElse(null));
        out.println("Quote for " + quote.lines().size() + " seat(s) at event " + operands.get(0));
        printQuote(quote);
    }

    private void hold(Session session, Args args, List<String> operands) {
        check(args, "hold", operands, 1, Set.of("customer", "seats", "best", "category", "section", "section-only",
                "accessible", "split", "ttl"));
        String customer = required(args, "customer", "hold");
        Duration ttl = args.value("ttl").map(Durations::parse).orElse(null);
        boolean explicit = args.value("seats").isPresent();
        if (explicit == args.value("best").isPresent()) {
            throw new UsageException("'hold' needs exactly one of --seats and --best");
        }
        Hold hold;
        if (explicit) {
            for (String option : List.of("category", "section", "accessible")) {
                if (args.value(option).isPresent()) {
                    throw new UsageException("option --" + option + " only works together with --best");
                }
            }
            if (args.flag("split") || args.flag("section-only")) {
                throw new UsageException("options --split and --section-only only work together with --best");
            }
            hold = session.service().hold(operands.get(0), seatList(args.value("seats").get()), customer, ttl);
        } else {
            hold = session.service().holdBestAvailable(operands.get(0), seatRequest(args), customer, ttl);
        }
        out.println("Held " + hold.id() + " for " + hold.customer() + ": " + join(hold.seats())
                + " (expires " + hold.expiresAt() + ")");
    }

    private static SeatRequest seatRequest(Args args) {
        SeatRequest request = SeatRequest.of(integer(args.value("best").get(), "--best"));
        if (args.value("category").isPresent()) {
            request = request.withCategory(args.value("category").get());
        }
        if (args.value("section").isPresent()) {
            String section = args.value("section").get();
            request = args.flag("section-only") ? request.onlyInSection(section) : request.preferringSection(section);
        } else if (args.flag("section-only")) {
            throw new UsageException("option --section-only needs --section");
        }
        if (args.value("accessible").isPresent()) {
            request = request.withAccessibleSeats(integer(args.value("accessible").get(), "--accessible"));
        }
        if (args.flag("split")) {
            request = request.allowingSplit();
        }
        return request;
    }

    private void confirm(Session session, Args args, List<String> operands) {
        check(args, "confirm", operands, 1, Set.of("promo"));
        Booking booking = session.service().confirm(operands.get(0), args.value("promo").orElse(null));
        out.println("Confirmed " + booking.id() + " for " + booking.customer() + ": " + join(booking.seats()));
        printQuote(booking.quote());
    }

    private void release(Session session, Args args, List<String> operands) {
        check(args, "release", operands, 1, Set.of());
        session.service().release(operands.get(0));
        out.println("Released " + operands.get(0));
    }

    private void cancel(Session session, Args args, List<String> operands) {
        check(args, "cancel", operands, 1, Set.of());
        Booking booking = session.service().cancel(operands.get(0));
        out.println("Cancelled " + booking.id() + ": refund " + booking.refund() + " of " + booking.total());
    }

    private void report(Session session, Args args, List<String> operands) {
        check(args, "report", operands, 1, Set.of());
        Event event = session.catalog().require(operands.get(0));
        OccupancyReport occupancy = session.service().occupancy(event.id());
        RevenueReport revenue = session.service().revenue(event.id());
        out.println(describe(event));
        out.println();
        out.println("Occupancy by section");
        out.print(occupancyTable(occupancy.sections(), occupancy.total()).render("  "));
        out.println();
        out.println("Occupancy by category");
        out.print(occupancyTable(occupancy.categories(), occupancy.total()).render("  "));
        out.println();
        out.println("Revenue");
        out.println("  " + revenue.confirmedBookings() + " confirmed and " + revenue.cancelledBookings()
                + " cancelled booking(s), " + revenue.seatsSold() + " seat(s) sold");
        TextTable money = new TextTable("lr")
                .row("gross", revenue.gross())
                .row("discounts", revenue.discounts())
                .row("sales", revenue.sales())
                .row("refunded", revenue.refunded())
                .row("kept from cancellations", revenue.cancellationIncome())
                .row("net", revenue.net());
        out.print(money.render("  "));
        TextTable categories = new TextTable("lrr");
        for (RevenueReport.CategorySales sales : revenue.categories()) {
            categories.row(sales.category(), sales.seats() + " seats", sales.gross());
        }
        out.println("  by category (list price):");
        out.print(categories.render("    "));
        if (!revenue.promoUses().isEmpty()) {
            StringJoiner uses = new StringJoiner(", ");
            revenue.promoUses().forEach((code, count) -> uses.add(code + " x" + count));
            out.println("  promo codes: " + uses);
        }
    }

    private static TextTable occupancyTable(List<OccupancyReport.Group> groups, OccupancyReport.Group total) {
        TextTable table = new TextTable("lrrrrr").row("", "Capacity", "Booked", "Held", "Free", "Booked %");
        List<OccupancyReport.Group> rows = new ArrayList<>(groups);
        rows.add(total);
        for (OccupancyReport.Group group : rows) {
            table.row(group.code(), group.capacity(), group.booked(), group.held(), group.free(),
                    String.format(Locale.ROOT, "%.1f", group.bookedPercent()));
        }
        return table;
    }

    private void audit(Session session, Args args, List<String> operands) {
        check(args, "audit", operands, 0, Set.of("event", "limit"));
        List<AuditEntry> entries = session.auditLog().entries();
        if (args.value("event").isPresent()) {
            String eventId = args.value("event").get();
            entries = entries.stream().filter(entry -> entry.eventId().equals(eventId)).toList();
        }
        if (args.value("limit").isPresent()) {
            int limit = integer(args.value("limit").get(), "--limit");
            if (limit < 1) {
                throw new UsageException("--limit must be at least 1");
            }
            entries = entries.subList(Math.max(0, entries.size() - limit), entries.size());
        }
        TextTable table = new TextTable("lllll");
        for (AuditEntry entry : entries) {
            table.row(entry.at(), entry.type(), entry.eventId(), entry.subject(), entry.detail());
        }
        out.print(table.render(""));
    }

    private void expire(Session session, Args args, List<String> operands) {
        check(args, "expire", operands, 0, Set.of());
        out.println("Removed " + session.service().expireHolds() + " expired hold(s)");
    }

    private void printQuote(Quote quote) {
        TextTable table = new TextTable("lr");
        for (Quote.Line line : quote.lines()) {
            table.row(line.seat() + " (" + line.category() + ")", line.price());
        }
        table.row("subtotal", quote.subtotal());
        for (Discount discount : quote.discounts()) {
            table.row(discount.label(), "-" + discount.amount());
        }
        table.row("total", quote.total());
        out.print(table.render("  "));
    }

    private static String describe(Event event) {
        return "Event " + event.id() + " \"" + event.name() + "\" at " + event.venue().name() + ", starts "
                + event.startsAt();
    }

    private static void check(Args args, String command, List<String> operands, int expected, Set<String> options) {
        Set<String> allowed = new HashSet<>(GLOBAL);
        allowed.addAll(options);
        args.allowOnly(command, allowed);
        if (operands.size() != expected) {
            throw new UsageException("'" + command + "' takes " + expected + " argument" + (expected == 1 ? "" : "s")
                    + ", got " + operands.size());
        }
    }

    private static String required(Args args, String option, String command) {
        return args.value(option)
                .orElseThrow(() -> new UsageException("'" + command + "' needs --" + option));
    }

    private static List<SeatId> seatList(String text) {
        List<SeatId> seats = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                seats.add(SeatId.parse(part));
            }
        }
        if (seats.isEmpty()) {
            throw new ValidationException("--seats needs at least one seat id");
        }
        return seats;
    }

    private static int integer(String text, String option) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new UsageException(option + " needs a whole number, got '" + text + "'");
        }
    }

    private static Path dataDirectory(String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new UsageException("--data is not a usable path: " + e.getReason());
        }
    }

    private static Clock fixedClock(String instant) {
        try {
            return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new UsageException("--now needs an ISO-8601 instant such as 2026-10-01T12:00:00Z, got '"
                    + instant + "'");
        }
    }

    private static String join(List<SeatId> seats) {
        StringJoiner joiner = new StringJoiner(" ");
        seats.forEach(seat -> joiner.add(seat.toString()));
        return joiner.toString();
    }
}
