package seatmap.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static seatmap.service.Fixtures.seats;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import seatmap.booking.Booking;
import seatmap.error.NotFoundException;
import seatmap.pricing.Money;
import seatmap.repo.InMemoryReservationRepository;
import seatmap.service.Fixtures;
import seatmap.service.MutableClock;
import seatmap.service.ReservationService;

class ReportsTest {
    private MutableClock clock;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Fixtures.NOW);
        service = new ReservationService(Fixtures.catalog(), new InMemoryReservationRepository(), clock);
    }

    @Test
    void emptyEventHasFullCapacityFree() {
        OccupancyReport report = service.occupancy("HAMLET");
        assertEquals(52, report.total().capacity());
        assertEquals(52, report.total().free());
        assertEquals(0.0, report.total().bookedPercent());
        assertEquals(36, report.sections().get(0).capacity());
        assertEquals("Orchestra", report.sections().get(0).name());
        assertEquals(12, report.categories().get(0).capacity());
        assertEquals("Premium", report.categories().get(0).name());

        RevenueReport revenue = service.revenue("HAMLET");
        assertEquals(0, revenue.confirmedBookings());
        assertEquals(Money.ZERO, revenue.net());
        assertEquals(2, revenue.categories().size());
        assertTrue(revenue.promoUses().isEmpty());
    }

    @Test
    void occupancyCountsHeldAndBookedSeatsPerGroup() {
        service.confirm(service.hold("HAMLET", seats("ORCH-A-4", "ORCH-A-5", "ORCH-A-3"), "Ann").id(), null);
        service.hold("HAMLET", seats("BALC-A-1", "BALC-A-2"), "Bob");
        service.hold("MACBETH", seats("BALC-A-3"), "Cy");

        OccupancyReport report = service.occupancy("HAMLET");
        OccupancyReport.Group orchestra = report.sections().get(0);
        OccupancyReport.Group balcony = report.sections().get(1);
        assertEquals(3, orchestra.booked());
        assertEquals(0, orchestra.held());
        assertEquals(33, orchestra.free());
        assertEquals(8.3, orchestra.bookedPercent());
        assertEquals(2, balcony.held());
        assertEquals(14, balcony.free());
        OccupancyReport.Group premium = report.categories().get(0);
        OccupancyReport.Group standard = report.categories().get(1);
        assertEquals(2, premium.booked());
        assertEquals(1, standard.booked());
        assertEquals(2, standard.held());
        assertEquals(new OccupancyReport.Group("TOTAL", "Total", 52, 3, 2), report.total());

        clock.advance(Duration.ofMinutes(11));
        assertEquals(0, service.occupancy("HAMLET").total().held());
    }

    @Test
    void revenueSeparatesSalesDiscountsAndCancellations() {
        // 2 PREM + 1 STD = 200.00, early-bird 10% -> 180.00
        Booking kept = service.confirm(
                service.hold("HAMLET", seats("ORCH-A-4", "ORCH-A-5", "ORCH-A-3"), "Ann").id(), null);
        // 1 STD = 40.00 - 10% = 36.00 - 25% promo = 27.00
        service.confirm(service.hold("HAMLET", seats("BALC-A-1"), "Bob").id(), "SPRING");
        // 1 PREM = 80.00 - 10% = 72.00, cancelled three days before: 50% refund
        Booking gone = service.confirm(service.hold("HAMLET", seats("ORCH-B-4"), "Cy").id(), null);
        clock.set(Fixtures.HAMLET_START.minus(Duration.ofDays(3)));
        service.cancel(gone.id());

        RevenueReport report = service.revenue("HAMLET");
        assertEquals(2, report.confirmedBookings());
        assertEquals(1, report.cancelledBookings());
        assertEquals(4, report.seatsSold());
        assertEquals(Money.parse("240.00"), report.gross());
        assertEquals(Money.parse("33.00"), report.discounts());
        assertEquals(Money.parse("207.00"), report.sales());
        assertEquals(Money.parse("36.00"), report.refunded());
        assertEquals(Money.parse("36.00"), report.cancellationIncome());
        assertEquals(Money.parse("243.00"), report.net());
        assertEquals(new RevenueReport.CategorySales("PREM", 2, Money.parse("160.00")), report.categories().get(0));
        assertEquals(new RevenueReport.CategorySales("STD", 2, Money.parse("80.00")), report.categories().get(1));
        assertEquals(Map.of("SPRING", 1), report.promoUses());
        assertEquals(Money.parse("180.00"), kept.total());
    }

    @Test
    void reportsRejectUnknownEvents() {
        assertThrows(NotFoundException.class, () -> service.occupancy("OTHELLO"));
        assertThrows(NotFoundException.class, () -> service.revenue("OTHELLO"));
    }
}
