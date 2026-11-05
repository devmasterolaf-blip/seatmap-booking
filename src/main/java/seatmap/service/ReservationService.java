package seatmap.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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
import seatmap.event.Event;
import seatmap.event.EventCatalog;
import seatmap.pricing.Money;
import seatmap.pricing.PricingEngine;
import seatmap.pricing.PromoCode;
import seatmap.pricing.Quote;
import seatmap.repo.ReservationRepository;
import seatmap.venue.Seat;
import seatmap.venue.SeatId;

/**
 * The entry point for selling seats: holds, confirmation, cancellation and availability.
 *
 * <p>All state lives in the {@link ReservationRepository}. Every operation that reads and
 * then changes the seats of an event runs under that event's lock, so two requests can
 * never both obtain the same seat. Time comes from the injected {@link Clock}.
 *
 * <p>Expired holds stop blocking seats the instant they expire; they are physically
 * removed the next time the event is touched or when {@link #expireHolds()} runs.
 */
public final class ReservationService {
    /** Largest number of seats a single hold may contain. */
    public static final int MAX_SEATS_PER_HOLD = 12;
    /** Longest customer name accepted. */
    public static final int MAX_CUSTOMER_LENGTH = 80;

    private final EventCatalog catalog;
    private final ReservationRepository repository;
    private final Clock clock;
    private final PricingEngine pricing = new PricingEngine();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReservationService(EventCatalog catalog, ReservationRepository repository, Clock clock) {
        this.catalog = catalog;
        this.repository = repository;
        this.clock = clock;
    }

    /** Holds the given seats for the event's default hold time. */
    public Hold hold(String eventId, List<SeatId> seats, String customer) {
        return hold(eventId, seats, customer, null);
    }

    /**
     * Holds the given seats.
     *
     * @param ttl how long the hold lasts, or {@code null} for the event's default; a hold
     *            never outlives the start of the event
     * @throws SeatUnavailableException if any of the seats is held or booked
     */
    public Hold hold(String eventId, List<SeatId> seats, String customer, Duration ttl) {
        Event event = catalog.require(eventId);
        String name = requireCustomer(customer);
        List<SeatId> wanted = requireSeats(event, seats);
        Duration lifetime = requireTtl(event, ttl);
        return locked(eventId, () -> {
            Instant now = clock.instant();
            requireOnSale(event, now);
            purgeExpired(eventId, now);
            Set<SeatId> occupied = occupied(eventId, now).keySet();
            List<SeatId> taken = wanted.stream().filter(occupied::contains).toList();
            if (!taken.isEmpty()) {
                throw new SeatUnavailableException(eventId, taken);
            }
            return createHold(event, wanted, name, lifetime, now);
        });
    }

    /** Gives up a hold. Releasing a hold that has already expired is not an error. */
    public void release(String holdId) {
        Hold hold = repository.findHold(holdId).orElseThrow(() -> new NotFoundException("hold", holdId));
        locked(hold.eventId(), () -> {
            if (!repository.deleteHold(holdId)) {
                throw new NotFoundException("hold", holdId);
            }
            return null;
        });
    }

    /**
     * Turns a hold into a booking, pricing it at the current time.
     *
     * @param promoCode a promo code to redeem, or {@code null}
     * @throws HoldExpiredException if the hold ran out before it was confirmed
     * @throws PromoCodeException   if the code is unknown, outside its window or used up
     */
    public Booking confirm(String holdId, String promoCode) {
        Hold found = repository.findHold(holdId).orElseThrow(() -> new NotFoundException("hold", holdId));
        Event event = catalog.require(found.eventId());
        return locked(event.id(), () -> {
            Instant now = clock.instant();
            Hold hold = repository.findHold(holdId).orElseThrow(() -> new NotFoundException("hold", holdId));
            if (hold.isExpired(now)) {
                repository.deleteHold(holdId);
                throw new HoldExpiredException(holdId, hold.expiresAt());
            }
            requireOnSale(event, now);
            PromoCode promo = resolvePromo(event, promoCode, now);
            Quote quote = pricing.quote(event.prices(), event.rules(), seatsOf(event, hold.seats()), now, promo);
            String id = String.format("B-%06d", repository.nextSequence("booking"));
            Booking booking = Booking.confirmed(id, event.id(), hold.customer(), quote,
                    promo == null ? null : promo.code(), now);
            repository.saveBooking(booking);
            repository.deleteHold(holdId);
            return booking;
        });
    }

    /**
     * Cancels a confirmed booking, frees its seats and computes the refund from the
     * event's refund policy.
     */
    public Booking cancel(String bookingId) {
        Booking found = repository.findBooking(bookingId)
                .orElseThrow(() -> new NotFoundException("booking", bookingId));
        Event event = catalog.require(found.eventId());
        return locked(event.id(), () -> {
            Instant now = clock.instant();
            Booking booking = repository.findBooking(bookingId)
                    .orElseThrow(() -> new NotFoundException("booking", bookingId));
            if (!booking.isConfirmed()) {
                throw new BookingStateException("booking " + bookingId + " is already cancelled");
            }
            requireOnSale(event, now);
            Money refund = event.refundPolicy().refundFor(booking.total(), now, event.startsAt());
            Booking cancelled = booking.cancelled(now, refund);
            repository.saveBooking(cancelled);
            return cancelled;
        });
    }

    /** Prices the given seats as if they were confirmed now, without reserving anything. */
    public Quote quote(String eventId, List<SeatId> seats, String promoCode) {
        Event event = catalog.require(eventId);
        List<SeatId> wanted = requireSeats(event, seats);
        Instant now = clock.instant();
        PromoCode promo = resolvePromo(event, promoCode, now);
        return pricing.quote(event.prices(), event.rules(), seatsOf(event, wanted), now, promo);
    }

    /** The status of every seat of the event, in seat-map order. */
    public Map<SeatId, SeatStatus> availability(String eventId) {
        Event event = catalog.require(eventId);
        return locked(eventId, () -> {
            Map<SeatId, SeatStatus> occupied = occupied(eventId, clock.instant());
            Map<SeatId, SeatStatus> result = new LinkedHashMap<>();
            for (Seat seat : event.venue().seats()) {
                result.put(seat.id(), occupied.getOrDefault(seat.id(), SeatStatus.FREE));
            }
            return result;
        });
    }

    /**
     * Removes every hold whose time has run out, across all events.
     *
     * @return the number of holds removed
     */
    public int expireHolds() {
        int removed = 0;
        for (Event event : catalog.all()) {
            removed += locked(event.id(), () -> purgeExpired(event.id(), clock.instant()));
        }
        return removed;
    }

    private Hold createHold(Event event, List<SeatId> seats, String customer, Duration lifetime, Instant now) {
        Instant expiresAt = now.plus(lifetime);
        if (expiresAt.isAfter(event.startsAt())) {
            expiresAt = event.startsAt();
        }
        String id = String.format("H-%06d", repository.nextSequence("hold"));
        Hold hold = new Hold(id, event.id(), customer, seats, now, expiresAt);
        repository.saveHold(hold);
        return hold;
    }

    private int purgeExpired(String eventId, Instant now) {
        int removed = 0;
        for (Hold hold : repository.holdsForEvent(eventId)) {
            if (hold.isExpired(now) && repository.deleteHold(hold.id())) {
                removed++;
            }
        }
        return removed;
    }

    /** Seats that are not free right now, with the reason. */
    private Map<SeatId, SeatStatus> occupied(String eventId, Instant now) {
        Map<SeatId, SeatStatus> occupied = new LinkedHashMap<>();
        for (Booking booking : repository.bookingsForEvent(eventId)) {
            if (booking.isConfirmed()) {
                booking.seats().forEach(seat -> occupied.put(seat, SeatStatus.BOOKED));
            }
        }
        for (Hold hold : repository.holdsForEvent(eventId)) {
            if (!hold.isExpired(now)) {
                hold.seats().forEach(seat -> occupied.put(seat, SeatStatus.HELD));
            }
        }
        return occupied;
    }

    private PromoCode resolvePromo(Event event, String code, Instant now) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized;
        try {
            normalized = PromoCode.normalize(code);
        } catch (ValidationException e) {
            throw new PromoCodeException(code, PromoCodeException.Reason.UNKNOWN,
                    "unknown promo code '" + code + "'");
        }
        PromoCode promo = event.promoCode(normalized).orElseThrow(() -> new PromoCodeException(normalized,
                PromoCodeException.Reason.UNKNOWN, "unknown promo code '" + normalized + "'"));
        int uses = 0;
        for (Booking booking : repository.bookingsForEvent(event.id())) {
            if (booking.isConfirmed() && promo.code().equals(booking.promoCode())) {
                uses++;
            }
        }
        promo.checkRedeemable(now, uses);
        return promo;
    }

    private static List<Seat> seatsOf(Event event, List<SeatId> ids) {
        List<Seat> seats = new ArrayList<>();
        for (SeatId id : ids) {
            seats.add(event.venue().seat(id).orElseThrow(() -> new NotFoundException("seat", id.toString())));
        }
        return seats;
    }

    private static void requireOnSale(Event event, Instant now) {
        if (event.hasStarted(now)) {
            throw new SalesClosedException(event.id());
        }
    }

    private static String requireCustomer(String customer) {
        if (customer == null || customer.isBlank()) {
            throw new ValidationException("customer name must not be blank");
        }
        String name = customer.strip();
        if (name.length() > MAX_CUSTOMER_LENGTH) {
            throw new ValidationException("customer name is longer than " + MAX_CUSTOMER_LENGTH + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new ValidationException("customer name must not contain control characters");
            }
        }
        return name;
    }

    private static List<SeatId> requireSeats(Event event, List<SeatId> seats) {
        if (seats == null || seats.isEmpty()) {
            throw new ValidationException("at least one seat is required");
        }
        if (seats.size() > MAX_SEATS_PER_HOLD) {
            throw new ValidationException("at most " + MAX_SEATS_PER_HOLD + " seats can be held at once, got "
                    + seats.size());
        }
        Set<SeatId> unique = new HashSet<>();
        for (SeatId seat : seats) {
            if (seat == null) {
                throw new ValidationException("seat ids must not be null");
            }
            if (!unique.add(seat)) {
                throw new ValidationException("seat " + seat + " is listed twice");
            }
            if (!event.venue().hasSeat(seat)) {
                throw new NotFoundException("seat", seat.toString());
            }
        }
        return List.copyOf(seats);
    }

    private static Duration requireTtl(Event event, Duration ttl) {
        if (ttl == null) {
            return event.holdTtl();
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new ValidationException("hold time must be positive");
        }
        return ttl;
    }

    private <T> T locked(String eventId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(eventId, id -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
