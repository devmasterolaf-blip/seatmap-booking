package seatmap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import seatmap.alloc.SeatRequest;
import seatmap.booking.Booking;
import seatmap.booking.Hold;
import seatmap.booking.SeatStatus;
import seatmap.error.HoldExpiredException;
import seatmap.error.NotFoundException;
import seatmap.error.PromoCodeException;
import seatmap.error.SeatUnavailableException;
import seatmap.repo.FileReservationRepository;
import seatmap.repo.InMemoryReservationRepository;
import seatmap.repo.ReservationRepository;
import seatmap.venue.SeatId;

/** Many threads compete for the same seats; no seat may ever be sold twice. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReservationConcurrencyTest {
    private static final int THREADS = 8;

    private ExecutorService pool;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(THREADS);
        clock = new MutableClock(Fixtures.NOW);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private ReservationService service(ReservationRepository repository) {
        return new ReservationService(Fixtures.catalog(), repository, clock);
    }

    /** Starts every task at the same moment and returns their results in task order. */
    private <T> List<T> race(List<Callable<T>> tasks) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                fail("task failed unexpectedly", e.getCause());
            }
        }
        return results;
    }

    @Test
    void onlyOneOfManyRequestsForTheSameSeatsWins() throws InterruptedException {
        ReservationService service = service(new InMemoryReservationRepository());
        AtomicInteger refused = new AtomicInteger();
        List<Callable<Hold>> tasks = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String customer = "customer " + i;
            tasks.add(() -> {
                try {
                    return service.hold("HAMLET", Fixtures.seats("ORCH-A-4", "ORCH-A-5"), customer);
                } catch (SeatUnavailableException e) {
                    refused.incrementAndGet();
                    return null;
                }
            });
        }

        List<Hold> holds = race(tasks).stream().filter(hold -> hold != null).toList();

        assertEquals(1, holds.size());
        assertEquals(31, refused.get());
    }

    @Test
    void overlappingRequestsNeverShareASeat() throws InterruptedException {
        ReservationService service = service(new InMemoryReservationRepository());
        List<Callable<Hold>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int first = 1 + i % 11;
            String customer = "customer " + i;
            tasks.add(() -> {
                try {
                    return service.hold("HAMLET",
                            Fixtures.seats("ORCH-B-" + first, "ORCH-B-" + (first + 1)), customer);
                } catch (SeatUnavailableException e) {
                    return null;
                }
            });
        }

        Set<SeatId> held = new HashSet<>();
        for (Hold hold : race(tasks)) {
            if (hold != null) {
                for (SeatId seat : hold.seats()) {
                    assertTrue(held.add(seat), "seat " + seat + " was held twice");
                }
            }
        }

        long heldNow = service.availability("HAMLET").values().stream()
                .filter(status -> status == SeatStatus.HELD).count();
        assertEquals(held.size(), heldNow);
    }

    @Test
    void bestAvailableSellsOutWithoutDoubleBooking() throws InterruptedException {
        ReservationService service = service(new InMemoryReservationRepository());
        int capacity = Fixtures.venue().capacity();
        List<Callable<Hold>> tasks = new ArrayList<>();
        for (int i = 0; i < capacity + 20; i++) {
            String customer = "customer " + i;
            tasks.add(() -> {
                try {
                    return service.holdBestAvailable("HAMLET", SeatRequest.of(1), customer, null);
                } catch (SeatUnavailableException e) {
                    return null;
                }
            });
        }

        Set<SeatId> held = new HashSet<>();
        int successes = 0;
        for (Hold hold : race(tasks)) {
            if (hold != null) {
                successes++;
                assertTrue(held.add(hold.seats().get(0)), "seat " + hold.seats() + " was allocated twice");
            }
        }

        assertEquals(capacity, successes);
        assertEquals(capacity, held.size());
    }

    @Test
    void aHoldIsConfirmedAtMostOnce() throws InterruptedException {
        ReservationService service = service(new InMemoryReservationRepository());
        Hold hold = service.hold("HAMLET", Fixtures.seats("ORCH-A-4", "ORCH-A-5"), "Ann");
        List<Callable<Booking>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tasks.add(() -> {
                try {
                    return service.confirm(hold.id(), null);
                } catch (NotFoundException e) {
                    return null;
                }
            });
        }

        List<Booking> bookings = race(tasks).stream().filter(booking -> booking != null).toList();

        assertEquals(1, bookings.size());
        assertEquals(1, service.revenue("HAMLET").confirmedBookings());
    }

    @Test
    void aLimitedPromoCodeIsNeverOverRedeemed() throws InterruptedException {
        ReservationService service = service(new InMemoryReservationRepository());
        List<Hold> holds = new ArrayList<>();
        for (int seat = 1; seat <= 8; seat++) {
            holds.add(service.hold("HAMLET", Fixtures.seats("BALC-A-" + seat), "customer " + seat));
        }
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (Hold hold : holds) {
            tasks.add(() -> {
                try {
                    service.confirm(hold.id(), "SPRING");
                    return true;
                } catch (PromoCodeException e) {
                    return false;
                }
            });
        }

        long redeemed = race(tasks).stream().filter(ok -> ok).count();

        assertEquals(2, redeemed, "SPRING allows two uses");
        assertEquals(2, service.revenue("HAMLET").promoUses().get("SPRING"));
    }

    @Test
    void confirmingAndExpiringRaceCleanly() throws InterruptedException {
        ReservationService service = service(new InMemoryReservationRepository());
        List<Hold> holds = new ArrayList<>();
        for (int seat = 1; seat <= 8; seat++) {
            holds.add(service.hold("HAMLET", Fixtures.seats("BALC-B-" + seat), "customer " + seat,
                    Duration.ofMinutes(seat)));
        }
        clock.advance(Duration.ofMinutes(4));
        List<Callable<String>> tasks = new ArrayList<>();
        for (Hold hold : holds) {
            tasks.add(() -> {
                try {
                    service.confirm(hold.id(), null);
                    return "confirmed";
                } catch (HoldExpiredException | NotFoundException e) {
                    return "gone";
                }
            });
            tasks.add(() -> {
                service.expireHolds();
                return "swept";
            });
        }

        List<String> outcomes = race(tasks);

        assertEquals(4, outcomes.stream().filter("confirmed"::equals).count(), "holds of 5 to 8 minutes survive");
        long booked = service.availability("HAMLET").values().stream()
                .filter(status -> status == SeatStatus.BOOKED).count();
        long held = service.availability("HAMLET").values().stream()
                .filter(status -> status == SeatStatus.HELD).count();
        assertEquals(4, booked);
        assertEquals(0, held);
    }

    @Test
    void fileBackedStateSurvivesParallelWriters(@TempDir Path dir) throws InterruptedException {
        Path file = dir.resolve("state.tsv");
        ReservationService service = service(new FileReservationRepository(file));
        List<Callable<Hold>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String event = i % 2 == 0 ? "HAMLET" : "MACBETH";
            String customer = "customer " + i;
            tasks.add(() -> service.holdBestAvailable(event, SeatRequest.of(2), customer, null));
        }

        List<Hold> holds = race(tasks);

        ReservationRepository reopened = new FileReservationRepository(file);
        assertEquals(12, reopened.holdsForEvent("HAMLET").size());
        assertEquals(12, reopened.holdsForEvent("MACBETH").size());
        Set<String> ids = new HashSet<>();
        holds.forEach(hold -> ids.add(hold.id()));
        assertEquals(24, ids.size(), "hold ids are unique");
        for (Hold hold : holds) {
            assertEquals(hold, reopened.findHold(hold.id()).orElseThrow());
        }
    }
}
