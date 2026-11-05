package seatmap.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import seatmap.alloc.SeatRequest;
import seatmap.audit.AuditEntry;
import seatmap.audit.FileAuditLog;
import seatmap.booking.Booking;
import seatmap.booking.Hold;
import seatmap.booking.SeatStatus;
import seatmap.error.HoldExpiredException;
import seatmap.error.SeatUnavailableException;
import seatmap.error.StorageException;
import seatmap.event.EventCatalog;
import seatmap.event.EventConfigParser;
import seatmap.pricing.Money;
import seatmap.report.OccupancyReport;
import seatmap.report.RevenueReport;
import seatmap.repo.FileReservationRepository;
import seatmap.service.Fixtures;
import seatmap.service.MutableClock;
import seatmap.service.ReservationService;
import seatmap.venue.SeatId;

/**
 * The whole stack on disk: configuration files are parsed, state and audit trail go to
 * files, and the service is torn down and rebuilt between steps the way separate
 * processes would do it.
 */
class RestartIntegrationTest {
    @TempDir
    Path dir;

    private MutableClock clock;

    @BeforeEach
    void writeConfiguration() throws IOException {
        Files.writeString(dir.resolve("orpheum.layout"), Fixtures.LAYOUT);
        Files.writeString(dir.resolve("events.conf"), Fixtures.EVENTS);
        clock = new MutableClock(Fixtures.NOW);
    }

    /** A fresh service reading everything from the directory again. */
    private ReservationService restart() {
        EventCatalog catalog = EventConfigParser.parse(dir.resolve("events.conf"));
        return new ReservationService(catalog, new FileReservationRepository(dir.resolve("state.tsv")), clock,
                new FileAuditLog(dir.resolve("audit.log")));
    }

    private List<AuditEntry> auditTrail() {
        return new FileAuditLog(dir.resolve("audit.log")).entries();
    }

    @Test
    void aSaleSurvivesRestartsBetweenEveryStep() {
        Hold hold = restart().hold("HAMLET", Fixtures.seats("ORCH-A-4", "ORCH-A-5"), "Ann Lee");

        ReservationService afterHold = restart();
        assertEquals(SeatStatus.HELD, afterHold.availability("HAMLET").get(SeatId.parse("ORCH-A-4")));
        assertThrows(SeatUnavailableException.class,
                () -> afterHold.hold("HAMLET", Fixtures.seats("ORCH-A-5"), "Bob"));

        clock.advance(Duration.ofMinutes(5));
        Booking booking = restart().confirm(hold.id(), "FIVER");
        assertEquals("B-000001", booking.id());
        assertEquals(Money.parse("139.00"), booking.total());

        ReservationService afterConfirm = restart();
        assertEquals(SeatStatus.BOOKED, afterConfirm.availability("HAMLET").get(SeatId.parse("ORCH-A-5")));
        RevenueReport revenue = afterConfirm.revenue("HAMLET");
        assertEquals(Money.parse("139.00"), revenue.net());
        assertEquals(1, revenue.promoUses().get("FIVER"));

        clock.advance(Duration.ofDays(1));
        Booking cancelled = restart().cancel(booking.id());
        assertEquals(Money.parse("139.00"), cancelled.refund());

        ReservationService afterCancel = restart();
        assertEquals(SeatStatus.FREE, afterCancel.availability("HAMLET").get(SeatId.parse("ORCH-A-4")));
        assertEquals(Money.parse("0.00"), afterCancel.revenue("HAMLET").net());
        assertEquals(List.of(AuditEntry.Type.HOLD_PLACED, AuditEntry.Type.BOOKING_CONFIRMED,
                AuditEntry.Type.BOOKING_CANCELLED), auditTrail().stream().map(AuditEntry::type).toList());
    }

    @Test
    void idsKeepCountingAfterARestart() {
        ReservationService first = restart();
        Hold one = first.hold("HAMLET", Fixtures.seats("BALC-A-1"), "Ann");
        first.release(one.id());
        first.confirm(first.hold("HAMLET", Fixtures.seats("BALC-A-2"), "Ann").id(), null);

        ReservationService second = restart();
        Hold three = second.hold("MACBETH", Fixtures.seats("BALC-A-1"), "Bob");

        assertEquals("H-000003", three.id());
        assertEquals("B-000002", second.confirm(three.id(), null).id());
    }

    @Test
    void aHoldThatRanOutWhileTheProcessWasDownIsGone() {
        Hold hold = restart().hold("HAMLET", Fixtures.seats("ORCH-A-4"), "Ann");

        clock.advance(Duration.ofMinutes(10));
        ReservationService later = restart();

        assertEquals(SeatStatus.FREE, later.availability("HAMLET").get(SeatId.parse("ORCH-A-4")));
        assertThrows(HoldExpiredException.class, () -> later.confirm(hold.id(), null));
        assertEquals(0, restart().expireHolds(), "the failed confirm already removed the hold");
        assertEquals(List.of(AuditEntry.Type.HOLD_PLACED, AuditEntry.Type.HOLD_EXPIRED),
                auditTrail().stream().map(AuditEntry::type).toList());
    }

    @Test
    void eventsShareAVenueButNotTheirSeats() {
        ReservationService service = restart();
        service.hold("HAMLET", Fixtures.seats("ORCH-A-4"), "Ann");

        Hold other = restart().hold("MACBETH", Fixtures.seats("ORCH-A-4"), "Bob");

        assertEquals("H-000002", other.id());
        OccupancyReport hamlet = restart().occupancy("HAMLET");
        OccupancyReport macbeth = restart().occupancy("MACBETH");
        assertEquals(1, hamlet.total().held());
        assertEquals(1, macbeth.total().held());
    }

    @Test
    void aSoldOutSectionSendsBestAvailableElsewhere() {
        ReservationService service = restart();
        for (String row : List.of("A", "B")) {
            service.confirm(service.holdBestAvailable("HAMLET",
                    SeatRequest.of(8).onlyInSection("BALC"), "Group " + row, null).id(), null);
        }

        ReservationService reopened = restart();
        assertEquals(0, reopened.occupancy("HAMLET").sections().get(1).free());
        assertThrows(SeatUnavailableException.class, () -> reopened.holdBestAvailable("HAMLET",
                SeatRequest.of(2).onlyInSection("BALC"), "Late", null));
        Hold fallback = reopened.holdBestAvailable("HAMLET", SeatRequest.of(2).preferringSection("BALC"), "Late",
                null);
        assertTrue(fallback.seats().stream().allMatch(seat -> seat.section().equals("ORCH")), fallback.toString());
    }

    @Test
    void aDamagedStateFileStopsTheServiceFromStarting() throws IOException {
        restart().hold("HAMLET", Fixtures.seats("ORCH-A-4"), "Ann");
        Path state = dir.resolve("state.tsv");
        Files.writeString(state, Files.readString(state).replace("ORCH-A-4", "ORCH-A-four"));

        StorageException e = assertThrows(StorageException.class, this::restart);

        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }
}
