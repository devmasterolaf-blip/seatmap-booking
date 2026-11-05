package seatmap.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static seatmap.repo.InMemoryReservationRepositoryTest.booking;
import static seatmap.repo.InMemoryReservationRepositoryTest.hold;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import seatmap.booking.Booking;
import seatmap.booking.Hold;
import seatmap.error.StorageException;
import seatmap.pricing.Discount;
import seatmap.pricing.Money;
import seatmap.pricing.Quote;
import seatmap.venue.SeatId;

class FileReservationRepositoryTest {
    private static final Instant T0 = Instant.parse("2026-05-01T10:00:00Z");

    @TempDir
    Path dir;
    private Path file;

    @BeforeEach
    void setUp() {
        file = dir.resolve("state.tsv");
    }

    @Test
    void startsEmptyWithoutAFile() {
        FileReservationRepository repo = new FileReservationRepository(file);
        assertTrue(repo.holdsForEvent("E1").isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void holdsSurviveReopening() {
        FileReservationRepository repo = new FileReservationRepository(file);
        Hold hold = new Hold("H-1", "E1", "Ann \"The Fan\"\tLee", List.of(SeatId.parse("A-1-1"), SeatId.parse("A-1-2")),
                T0, T0.plusSeconds(600));
        repo.saveHold(hold);
        repo.saveHold(hold("H-2", "E1", "A-1-3"));
        repo.deleteHold("H-2");

        FileReservationRepository reopened = new FileReservationRepository(file);
        assertEquals(List.of(hold), reopened.holdsForEvent("E1"));
        assertTrue(reopened.findHold("H-2").isEmpty());
        assertFalse(reopened.deleteHold("H-2"));
    }

    @Test
    void bookingsSurviveReopeningWithDiscountsAndCancellation() {
        FileReservationRepository repo = new FileReservationRepository(file);
        Quote quote = new Quote(
                List.of(new Quote.Line(SeatId.parse("A-1-1"), "PREM", Money.parse("80")),
                        new Quote.Line(SeatId.parse("A-1-2"), "STD", Money.parse("40"))),
                Money.parse("120"),
                List.of(new Discount("early-bird 10%", Money.parse("12")), new Discount("promo SPRING", Money.parse("27"))),
                Money.parse("81"));
        Booking confirmed = Booking.confirmed("B-1", "E1", "Ann", quote, "SPRING", T0);
        Booking cancelled = booking("B-2", "E1", "A-1-3").cancelled(T0.plusSeconds(90), Money.parse("20"));
        repo.saveBooking(confirmed);
        repo.saveBooking(cancelled);

        FileReservationRepository reopened = new FileReservationRepository(file);
        assertEquals(confirmed, reopened.findBooking("B-1").orElseThrow());
        assertEquals(cancelled, reopened.findBooking("B-2").orElseThrow());
        assertEquals(List.of("B-1", "B-2"), reopened.bookingsForEvent("E1").stream().map(Booking::id).toList());
    }

    @Test
    void sequencesContinueAfterReopening() {
        FileReservationRepository repo = new FileReservationRepository(file);
        assertEquals(1, repo.nextSequence("hold"));
        assertEquals(2, repo.nextSequence("hold"));
        assertEquals(1, repo.nextSequence("booking"));
        FileReservationRepository reopened = new FileReservationRepository(file);
        assertEquals(3, reopened.nextSequence("hold"));
        assertEquals(2, reopened.nextSequence("booking"));
    }

    @Test
    void leavesNoTemporaryFileBehind() throws Exception {
        FileReservationRepository repo = new FileReservationRepository(file);
        repo.saveHold(hold("H-1", "E1", "A-1-1"));
        try (var files = Files.list(dir)) {
            assertEquals(List.of("state.tsv"), files.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals("seatmap-state 1", Files.readAllLines(file).get(0));
    }

    @Test
    void rejectsForeignAndCorruptFiles() throws Exception {
        Files.writeString(file, "something else\n");
        assertTrue(assertThrows(StorageException.class, () -> new FileReservationRepository(file))
                .getMessage().contains("does not start with"));
        Files.writeString(file, "seatmap-state 1\nseq\thold\t3\nhold\tH-1\tE1\tAnn\tnot-a-time\tx\tA-1-1\n");
        assertTrue(assertThrows(StorageException.class, () -> new FileReservationRepository(file))
                .getMessage().contains("line 3"));
        Files.writeString(file, "seatmap-state 1\nwidget\t1\n");
        assertTrue(assertThrows(StorageException.class, () -> new FileReservationRepository(file))
                .getMessage().contains("unknown record type 'widget'"));
        Files.writeString(file, "seatmap-state 1\nseq\thold\tmany\n");
        assertThrows(StorageException.class, () -> new FileReservationRepository(file));
        Files.writeString(file, "seatmap-state 1\nbooking\tB-1\tE1\n");
        assertThrows(StorageException.class, () -> new FileReservationRepository(file));
    }
}
