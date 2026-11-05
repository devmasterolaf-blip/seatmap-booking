package seatmap.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import seatmap.booking.Booking;
import seatmap.booking.Hold;
import seatmap.error.SeatmapException;
import seatmap.error.StorageException;
import seatmap.pricing.Discount;
import seatmap.pricing.Money;
import seatmap.pricing.Quote;
import seatmap.text.Fields;
import seatmap.venue.SeatId;

/**
 * A repository that keeps its contents in a single text file.
 *
 * <p>The whole state is loaded when the repository is opened and rewritten after every
 * change: the new contents go to a temporary file next to the target, which then replaces
 * it. The file has a header line followed by one tab-separated record per sequence, hold
 * and booking. It is meant for one process at a time; nothing guards against two
 * processes writing the same file.
 */
public final class FileReservationRepository implements ReservationRepository {
    private static final String HEADER = "seatmap-state 1";
    private static final String NONE = "-";

    private final Path file;
    private final InMemoryReservationRepository state = new InMemoryReservationRepository();

    /** Opens the repository, reading {@code file} if it exists. */
    public FileReservationRepository(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            load();
        }
    }

    @Override
    public synchronized long nextSequence(String name) {
        long value = state.nextSequence(name);
        flush();
        return value;
    }

    @Override
    public synchronized void saveHold(Hold hold) {
        state.saveHold(hold);
        flush();
    }

    @Override
    public synchronized Optional<Hold> findHold(String id) {
        return state.findHold(id);
    }

    @Override
    public synchronized boolean deleteHold(String id) {
        boolean existed = state.deleteHold(id);
        if (existed) {
            flush();
        }
        return existed;
    }

    @Override
    public synchronized List<Hold> holdsForEvent(String eventId) {
        return state.holdsForEvent(eventId);
    }

    @Override
    public synchronized void saveBooking(Booking booking) {
        state.saveBooking(booking);
        flush();
    }

    @Override
    public synchronized Optional<Booking> findBooking(String id) {
        return state.findBooking(id);
    }

    @Override
    public synchronized List<Booking> bookingsForEvent(String eventId) {
        return state.bookingsForEvent(eventId);
    }

    private void flush() {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        new TreeMap<>(state.sequences()).forEach((name, value) ->
                text.append(Fields.join(List.of("seq", name, Long.toString(value)))).append('\n'));
        for (Hold hold : state.allHolds()) {
            text.append(Fields.join(encode(hold))).append('\n');
        }
        for (Booking booking : state.allBookings()) {
            text.append(Fields.join(encode(booking))).append('\n');
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("cannot write state file " + file + ": " + e.getMessage(), e);
        }
    }

    private void load() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("cannot read state file " + file + ": " + e.getMessage(), e);
        }
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            throw new StorageException("state file " + file + " does not start with '" + HEADER + "'");
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) {
                continue;
            }
            try {
                List<String> fields = Fields.split(lines.get(i));
                switch (fields.get(0)) {
                    case "seq" -> {
                        expect(fields.size() == 3, "a seq record has 3 fields");
                        state.restoreSequence(fields.get(1), Long.parseLong(fields.get(2)));
                    }
                    case "hold" -> state.saveHold(decodeHold(fields));
                    case "booking" -> state.saveBooking(decodeBooking(fields));
                    default -> throw new StorageException("unknown record type '" + fields.get(0) + "'");
                }
            } catch (SeatmapException | DateTimeParseException | IllegalArgumentException e) {
                throw new StorageException("state file " + file + " is corrupt at line " + (i + 1) + ": "
                        + e.getMessage(), e);
            }
        }
    }

    private static List<String> encode(Hold hold) {
        return List.of("hold", hold.id(), hold.eventId(), hold.customer(), hold.createdAt().toString(),
                hold.expiresAt().toString(), joinSeats(hold.seats()));
    }

    private static Hold decodeHold(List<String> fields) {
        expect(fields.size() == 7, "a hold record has 7 fields");
        List<SeatId> seats = new ArrayList<>();
        for (String seat : fields.get(6).split(",")) {
            seats.add(SeatId.parse(seat));
        }
        return new Hold(fields.get(1), fields.get(2), fields.get(3), seats, Instant.parse(fields.get(4)),
                Instant.parse(fields.get(5)));
    }

    private static List<String> encode(Booking booking) {
        List<String> fields = new ArrayList<>(List.of("booking", booking.id(), booking.eventId(),
                booking.customer(), booking.status().name(), booking.confirmedAt().toString(),
                booking.cancelledAt() == null ? NONE : booking.cancelledAt().toString(),
                Long.toString(booking.refund().cents()),
                booking.promoCode() == null ? NONE : booking.promoCode(),
                Long.toString(booking.quote().subtotal().cents()),
                Long.toString(booking.quote().total().cents())));
        StringBuilder lines = new StringBuilder();
        for (Quote.Line line : booking.quote().lines()) {
            if (lines.length() > 0) {
                lines.append(',');
            }
            lines.append(line.seat()).append(':').append(line.category()).append(':').append(line.price().cents());
        }
        fields.add(lines.toString());
        for (Discount discount : booking.quote().discounts()) {
            fields.add(discount.label());
            fields.add(Long.toString(discount.amount().cents()));
        }
        return fields;
    }

    private static Booking decodeBooking(List<String> fields) {
        expect(fields.size() >= 12 && fields.size() % 2 == 0,
                "a booking record has 12 fields plus two per discount");
        List<Quote.Line> lines = new ArrayList<>();
        for (String line : fields.get(11).split(",")) {
            String[] parts = line.split(":");
            expect(parts.length == 3, "a booking line looks like SEAT:CATEGORY:CENTS");
            lines.add(new Quote.Line(SeatId.parse(parts[0]), parts[1], Money.ofCents(Long.parseLong(parts[2]))));
        }
        List<Discount> discounts = new ArrayList<>();
        for (int i = 12; i < fields.size(); i += 2) {
            discounts.add(new Discount(fields.get(i), Money.ofCents(Long.parseLong(fields.get(i + 1)))));
        }
        Quote quote = new Quote(lines, Money.ofCents(Long.parseLong(fields.get(9))), discounts,
                Money.ofCents(Long.parseLong(fields.get(10))));
        return new Booking(fields.get(1), fields.get(2), fields.get(3), quote,
                fields.get(8).equals(NONE) ? null : fields.get(8), Instant.parse(fields.get(5)),
                Booking.Status.valueOf(fields.get(4)),
                fields.get(6).equals(NONE) ? null : Instant.parse(fields.get(6)),
                Money.ofCents(Long.parseLong(fields.get(7))));
    }

    private static String joinSeats(List<SeatId> seats) {
        StringBuilder text = new StringBuilder();
        for (SeatId seat : seats) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(seat);
        }
        return text.toString();
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new StorageException(message);
        }
    }
}
