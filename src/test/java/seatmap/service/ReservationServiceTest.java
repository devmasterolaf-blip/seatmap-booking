package seatmap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static seatmap.service.Fixtures.seats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import seatmap.alloc.SeatRequest;
import seatmap.audit.AuditEntry;
import seatmap.audit.InMemoryAuditLog;
import seatmap.booking.Booking;
import seatmap.booking.Hold;
import seatmap.booking.SeatStatus;
import seatmap.error.BookingStateException;
import seatmap.error.HoldExpiredException;
import seatmap.error.NotFoundException;
import seatmap.error.PromoCodeException;
import seatmap.error.SalesClosedException;
import seatmap.error.SeatUnavailableException;
import seatmap.error.ValidationException;
import seatmap.pricing.Money;
import seatmap.pricing.Quote;
import seatmap.repo.InMemoryReservationRepository;
import seatmap.venue.SeatId;

class ReservationServiceTest {
    private MutableClock clock;
    private InMemoryReservationRepository repository;
    private InMemoryAuditLog audit;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Fixtures.NOW);
        repository = new InMemoryReservationRepository();
        audit = new InMemoryAuditLog();
        service = new ReservationService(Fixtures.catalog(), repository, clock, audit);
    }

    private SeatStatus status(String event, String seat) {
        return service.availability(event).get(SeatId.parse(seat));
    }

    // ---- holds ----

    @Test
    void holdBlocksSeatsUntilItExpires() {
        Hold hold = service.hold("HAMLET", seats("ORCH-A-4", "ORCH-A-5"), "  Ann Lee ");
        assertEquals("H-000001", hold.id());
        assertEquals("Ann Lee", hold.customer());
        assertEquals(Fixtures.NOW.plus(Duration.ofMinutes(10)), hold.expiresAt());
        assertEquals(SeatStatus.HELD, status("HAMLET", "ORCH-A-4"));
        assertEquals(SeatStatus.FREE, status("HAMLET", "ORCH-A-6"));
        assertEquals(SeatStatus.FREE, status("MACBETH", "ORCH-A-4"));

        SeatUnavailableException e = assertThrows(SeatUnavailableException.class,
                () -> service.hold("HAMLET", seats("ORCH-A-5", "ORCH-A-6"), "Bob"));
        assertEquals(seats("ORCH-A-5"), e.seats());
        assertEquals(SeatStatus.FREE, status("HAMLET", "ORCH-A-6"));

        clock.advance(Duration.ofMinutes(10));
        assertEquals(SeatStatus.FREE, status("HAMLET", "ORCH-A-4"));
        assertEquals("H-000002", service.hold("HAMLET", seats("ORCH-A-5", "ORCH-A-6"), "Bob").id());
        assertTrue(repository.findHold("H-000001").isEmpty(), "expired hold is purged when the event is touched");
    }

    @Test
    void customTtlIsHonouredAndCappedAtEventStart() {
        Hold shortHold = service.hold("HAMLET", seats("ORCH-A-1"), "Ann", Duration.ofSeconds(30));
        assertEquals(Fixtures.NOW.plusSeconds(30), shortHold.expiresAt());
        clock.set(Fixtures.HAMLET_START.minusSeconds(60));
        Hold late = service.hold("HAMLET", seats("ORCH-A-2"), "Ann");
        assertEquals(Fixtures.HAMLET_START, late.expiresAt());
        assertThrows(ValidationException.class,
                () -> service.hold("HAMLET", seats("ORCH-A-3"), "Ann", Duration.ZERO));
    }

    @Test
    void holdValidatesItsInput() {
        assertThrows(NotFoundException.class, () -> service.hold("OTHELLO", seats("ORCH-A-1"), "Ann"));
        assertEquals("seat", assertThrows(NotFoundException.class,
                () -> service.hold("HAMLET", seats("ORCH-Z-1"), "Ann")).kind());
        assertThrows(ValidationException.class, () -> service.hold("HAMLET", List.of(), "Ann"));
        assertThrows(ValidationException.class, () -> service.hold("HAMLET", seats("ORCH-A-1", "ORCH-A-1"), "Ann"));
        assertThrows(ValidationException.class, () -> service.hold("HAMLET", seats("ORCH-A-1"), "  "));
        assertThrows(ValidationException.class, () -> service.hold("HAMLET", seats("ORCH-A-1"), "Ann\nLee"));
        assertThrows(ValidationException.class, () -> service.hold("HAMLET", seats("ORCH-A-1"), "x".repeat(81)));
        List<SeatId> tooMany = new ArrayList<>(seats("ORCH-A-1", "ORCH-A-2", "ORCH-A-3", "ORCH-A-4", "ORCH-A-5",
                "ORCH-A-6", "ORCH-A-7", "ORCH-A-8", "ORCH-A-9", "ORCH-A-10", "ORCH-A-11", "ORCH-A-12", "ORCH-B-1"));
        assertThrows(ValidationException.class, () -> service.hold("HAMLET", tooMany, "Ann"));
        assertTrue(repository.allHolds().isEmpty());
    }

    @Test
    void releaseFreesSeats() {
        Hold hold = service.hold("HAMLET", seats("ORCH-A-4"), "Ann");
        service.release(hold.id());
        assertEquals(SeatStatus.FREE, status("HAMLET", "ORCH-A-4"));
        assertThrows(NotFoundException.class, () -> service.release(hold.id()));
    }

    @Test
    void expireHoldsSweepsAllEvents() {
        service.hold("HAMLET", seats("ORCH-A-4"), "Ann");
        service.hold("MACBETH", seats("ORCH-A-4"), "Bob", Duration.ofMinutes(30));
        service.hold("MACBETH", seats("ORCH-A-5"), "Cy");
        assertEquals(0, service.expireHolds());
        clock.advance(Duration.ofMinutes(15));
        assertEquals(2, service.expireHolds());
        assertEquals(1, repository.allHolds().size());
    }

    // ---- best available ----

    @Test
    void holdBestAvailableSkipsSeatsThatAreAlreadyTaken() {
        Hold first = service.holdBestAvailable("HAMLET", SeatRequest.of(2).withCategory("PREM"), "Ann", null);
        assertEquals(seats("ORCH-A-6", "ORCH-A-7"), first.seats());
        Hold second = service.holdBestAvailable("HAMLET", SeatRequest.of(2).withCategory("PREM"), "Bob", null);
        assertEquals(seats("ORCH-A-4", "ORCH-A-5"), second.seats());
        service.confirm(first.id(), null);
        service.release(second.id());
        Hold third = service.holdBestAvailable("HAMLET", SeatRequest.of(4).withCategory("PREM"), "Cy", null);
        assertEquals(seats("ORCH-B-4", "ORCH-B-5", "ORCH-B-6", "ORCH-B-7"), third.seats());
    }

    @Test
    void holdBestAvailableReportsWhatCouldNotBeFound() {
        SeatUnavailableException e = assertThrows(SeatUnavailableException.class, () -> service.holdBestAvailable(
                "HAMLET", SeatRequest.of(7).withCategory("PREM").onlyInSection("ORCH"), "Ann", null));
        assertEquals("no 7 adjacent free seats in category PREM in section ORCH for event HAMLET", e.getMessage());
        assertTrue(e.seats().isEmpty());
        assertEquals("category", assertThrows(NotFoundException.class,
                () -> service.holdBestAvailable("HAMLET", SeatRequest.of(2).withCategory("VIP"), "Ann", null)).kind());
        assertEquals("section", assertThrows(NotFoundException.class,
                () -> service.holdBestAvailable("HAMLET", SeatRequest.of(2).preferringSection("PIT"), "Ann", null)).kind());
        assertThrows(ValidationException.class,
                () -> service.holdBestAvailable("HAMLET", SeatRequest.of(13).allowingSplit(), "Ann", null));
        assertThrows(ValidationException.class, () -> service.holdBestAvailable("HAMLET", null, "Ann", null));
    }

    // ---- confirmation ----

    @Test
    void confirmTurnsAHoldIntoABooking() {
        Hold hold = service.hold("HAMLET", seats("ORCH-A-4", "ORCH-A-3"), "Ann");
        clock.advance(Duration.ofMinutes(5));
        Booking booking = service.confirm(hold.id(), null);
        assertEquals("B-000001", booking.id());
        assertEquals("Ann", booking.customer());
        assertEquals(seats("ORCH-A-4", "ORCH-A-3"), booking.seats());
        assertEquals(Money.parse("120.00"), booking.quote().subtotal());
        assertEquals(Money.parse("108.00"), booking.total(), "early-bird 10% applies in October");
        assertNull(booking.promoCode());
        assertEquals(clock.instant(), booking.confirmedAt());
        assertEquals(SeatStatus.BOOKED, status("HAMLET", "ORCH-A-4"));
        assertTrue(repository.findHold(hold.id()).isEmpty());
        assertThrows(NotFoundException.class, () -> service.confirm(hold.id(), null));
    }

    @Test
    void bookedSeatsStayBookedAfterTheHoldTimePasses() {
        Hold hold = service.hold("HAMLET", seats("ORCH-A-4"), "Ann");
        service.confirm(hold.id(), null);
        clock.advance(Duration.ofHours(5));
        assertEquals(SeatStatus.BOOKED, status("HAMLET", "ORCH-A-4"));
        assertThrows(SeatUnavailableException.class, () -> service.hold("HAMLET", seats("ORCH-A-4"), "Bob"));
    }

    @Test
    void confirmingAnExpiredHoldFailsAndFreesTheSeats() {
        Hold hold = service.hold("HAMLET", seats("ORCH-A-4"), "Ann");
        clock.advance(Duration.ofMinutes(10));
        HoldExpiredException e = assertThrows(HoldExpiredException.class, () -> service.confirm(hold.id(), null));
        assertEquals(hold.id(), e.holdId());
        assertEquals(SeatStatus.FREE, status("HAMLET", "ORCH-A-4"));
        assertThrows(NotFoundException.class, () -> service.confirm(hold.id(), null));
    }

    @Test
    void promoCodeIsAppliedAndRecorded() {
        Hold hold = service.hold("HAMLET", seats("ORCH-A-1"), "Ann");
        Booking booking = service.confirm(hold.id(), "spring");
        assertEquals("SPRING", booking.promoCode());
        // 40.00 - 10% early-bird = 36.00, then 25% promo = 27.00
        assertEquals(Money.parse("27.00"), booking.total());
        assertEquals(2, booking.quote().discounts().size());
    }

    @Test
    void rejectedPromoCodeKeepsTheHold() {
        Hold hold = service.hold("HAMLET", seats("ORCH-A-1"), "Ann");
        assertEquals(PromoCodeException.Reason.UNKNOWN,
                assertThrows(PromoCodeException.class, () -> service.confirm(hold.id(), "NOPE")).reason());
        assertEquals(PromoCodeException.Reason.UNKNOWN,
                assertThrows(PromoCodeException.class, () -> service.confirm(hold.id(), "not a code")).reason());
        assertEquals(SeatStatus.HELD, status("HAMLET", "ORCH-A-1"));
        assertEquals("B-000001", service.confirm(hold.id(), " ").id(), "blank promo means none");
    }

    @Test
    void promoUseLimitCountsOnlyLiveBookings() {
        Booking first = service.confirm(service.hold("HAMLET", seats("ORCH-A-1"), "A").id(), "SPRING");
        service.confirm(service.hold("HAMLET", seats("ORCH-A-2"), "B").id(), "SPRING");
        Hold third = service.hold("HAMLET", seats("ORCH-A-3"), "C");
        assertEquals(PromoCodeException.Reason.EXHAUSTED,
                assertThrows(PromoCodeException.class, () -> service.confirm(third.id(), "SPRING")).reason());
        service.cancel(first.id());
        assertEquals("SPRING", service.confirm(third.id(), "SPRING").promoCode());
    }

    @Test
    void salesCloseWhenTheEventStarts() {
        Hold hold = service.hold("HAMLET", seats("ORCH-A-1"), "Ann", Duration.ofDays(90));
        Booking booking = service.confirm(service.hold("HAMLET", seats("ORCH-A-2"), "Bob").id(), null);
        clock.set(Fixtures.HAMLET_START);
        assertThrows(SalesClosedException.class, () -> service.hold("HAMLET", seats("ORCH-A-3"), "Cy"));
        assertThrows(HoldExpiredException.class, () -> service.confirm(hold.id(), null));
        assertThrows(SalesClosedException.class, () -> service.cancel(booking.id()));
    }

    // ---- cancellation ----

    @Test
    void cancelRefundsAccordingToThePolicyTier() {
        Booking full = service.confirm(service.hold("HAMLET", seats("ORCH-A-4"), "Ann").id(), null);
        Booking half = service.confirm(service.hold("HAMLET", seats("ORCH-A-5"), "Bob").id(), null);
        Booking none = service.confirm(service.hold("HAMLET", seats("ORCH-A-6"), "Cy").id(), null);

        Booking cancelledFull = service.cancel(full.id());
        assertEquals(Money.parse("72.00"), cancelledFull.refund());
        assertEquals(Money.ZERO, cancelledFull.retained());
        assertEquals(clock.instant(), cancelledFull.cancelledAt());

        clock.set(Fixtures.HAMLET_START.minus(Duration.ofDays(3)));
        assertEquals(Money.parse("36.00"), service.cancel(half.id()).refund());

        clock.set(Fixtures.HAMLET_START.minus(Duration.ofHours(2)));
        Booking cancelledNone = service.cancel(none.id());
        assertEquals(Money.ZERO, cancelledNone.refund());
        assertEquals(Money.parse("72.00"), cancelledNone.retained());
    }

    @Test
    void cancelFreesSeatsAndCannotBeRepeated() {
        Booking booking = service.confirm(service.hold("HAMLET", seats("ORCH-A-4"), "Ann").id(), null);
        service.cancel(booking.id());
        assertEquals(SeatStatus.FREE, status("HAMLET", "ORCH-A-4"));
        assertThrows(BookingStateException.class, () -> service.cancel(booking.id()));
        assertThrows(NotFoundException.class, () -> service.cancel("B-999999"));
        assertFalse(repository.findBooking(booking.id()).orElseThrow().isConfirmed());
        service.hold("HAMLET", seats("ORCH-A-4"), "Bob");
    }

    // ---- audit trail ----

    @Test
    void everyStateChangeIsAudited() {
        Hold kept = service.hold("HAMLET", seats("ORCH-A-4", "ORCH-A-5"), "Ann");
        Hold released = service.hold("HAMLET", seats("ORCH-A-6"), "Bob");
        Hold lapsed = service.hold("HAMLET", seats("ORCH-A-7"), "Cy", Duration.ofMinutes(1));
        service.release(released.id());
        Booking booking = service.confirm(kept.id(), "FIVER");
        clock.advance(Duration.ofMinutes(2));
        service.expireHolds();
        service.cancel(booking.id());

        List<AuditEntry> entries = audit.entries();
        assertEquals(List.of(AuditEntry.Type.HOLD_PLACED, AuditEntry.Type.HOLD_PLACED, AuditEntry.Type.HOLD_PLACED,
                AuditEntry.Type.HOLD_RELEASED, AuditEntry.Type.BOOKING_CONFIRMED, AuditEntry.Type.HOLD_EXPIRED,
                AuditEntry.Type.BOOKING_CANCELLED), entries.stream().map(AuditEntry::type).toList());
        assertEquals("customer=Ann seats=ORCH-A-4,ORCH-A-5 expires=2026-10-01T12:10:00Z", entries.get(0).detail());
        assertEquals("hold=H-000001 customer=Ann seats=ORCH-A-4,ORCH-A-5 total=139.00 promo=FIVER",
                entries.get(4).detail());
        assertEquals(lapsed.id(), entries.get(5).subject());
        assertEquals("customer=Ann seats=ORCH-A-4,ORCH-A-5 refund=139.00", entries.get(6).detail());
        assertTrue(entries.stream().allMatch(e -> e.eventId().equals("HAMLET")));
    }

    @Test
    void failedOperationsLeaveNoAuditEntries() {
        service.hold("HAMLET", seats("ORCH-A-4"), "Ann");
        assertThrows(SeatUnavailableException.class, () -> service.hold("HAMLET", seats("ORCH-A-4"), "Bob"));
        assertThrows(NotFoundException.class, () -> service.release("H-000099"));
        assertEquals(1, audit.entries().size());
    }

    // ---- quotes and availability ----

    @Test
    void quoteDoesNotReserveAnything() {
        Quote quote = service.quote("HAMLET",
                seats("ORCH-A-4", "ORCH-A-5", "ORCH-A-6", "ORCH-A-7", "ORCH-A-8", "ORCH-A-9"), "FIVER");
        assertEquals(Money.parse("480.00"), quote.subtotal());
        // group 20% (96.00) beats early-bird 10% (48.00); then 5.00 off
        assertEquals(Money.parse("379.00"), quote.total());
        assertEquals(SeatStatus.FREE, status("HAMLET", "ORCH-A-4"));
        assertThrows(PromoCodeException.class, () -> service.quote("HAMLET", seats("ORCH-A-4"), "NOPE"));
    }

    @Test
    void availabilityListsEverySeatInMapOrder() {
        Map<SeatId, SeatStatus> availability = service.availability("HAMLET");
        assertEquals(52, availability.size());
        assertEquals(SeatId.parse("ORCH-A-1"), availability.keySet().iterator().next());
        assertTrue(availability.values().stream().allMatch(s -> s == SeatStatus.FREE));
        assertThrows(NotFoundException.class, () -> service.availability("OTHELLO"));
    }
}
