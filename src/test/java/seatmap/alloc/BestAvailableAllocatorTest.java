package seatmap.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import seatmap.error.ValidationException;
import seatmap.venue.Seat;
import seatmap.venue.SeatId;
import seatmap.venue.Venue;
import seatmap.venue.VenueLayoutParser;

class BestAvailableAllocatorTest {
    private static final Venue VENUE = VenueLayoutParser.parse("""
            venue "Test Hall"
            category PREM
            category STD
            section FLOOR
              row A 1-3:STD | 4-9:PREM | 10-12:STD
              row B 1-3:STD | 4-9:PREM | 10-12:STD
              row C 1-2:STD+acc 3:STD | 4-9:STD | 10:STD 11-12:STD+acc
            section BALC
              row A 1-8:STD
              row B 1-8:STD
            """, "test.layout");

    private final BestAvailableAllocator allocator = new BestAvailableAllocator();

    private static Set<SeatId> occupied(String... ids) {
        return Arrays.stream(ids).map(SeatId::parse).collect(Collectors.toCollection(HashSet::new));
    }

    private String allocate(SeatRequest request, String... occupied) {
        Optional<List<Seat>> seats = allocator.allocate(VENUE, occupied(occupied), request);
        return seats.map(list -> list.stream().map(s -> s.id().toString()).collect(Collectors.joining(" ")))
                .orElse("none");
    }

    @Test
    void picksTheMiddleOfTheFrontRow() {
        assertEquals("FLOOR-A-6 FLOOR-A-7", allocate(SeatRequest.of(2)));
        assertEquals("FLOOR-A-4 FLOOR-A-5 FLOOR-A-6 FLOOR-A-7 FLOOR-A-8 FLOOR-A-9", allocate(SeatRequest.of(6)));
    }

    @Test
    void oddPartyHugsABlockEdgeRatherThanStrandingASeat() {
        // 5-7 and 6-8 are the most central, but each strands one seat; 4-6 and 7-9 do not,
        // and the leftmost of those two wins the tie
        assertEquals("FLOOR-A-4 FLOOR-A-5 FLOOR-A-6", allocate(SeatRequest.of(3)));
    }

    @Test
    void neverCrossesAnAisle() {
        // seven seats do not fit in any FLOOR block (max 6), so the party goes to the balcony
        assertEquals("BALC-A-1 BALC-A-2 BALC-A-3 BALC-A-4 BALC-A-5 BALC-A-6 BALC-A-7", allocate(SeatRequest.of(7)));
        assertEquals("none", allocate(SeatRequest.of(9)));
    }

    @Test
    void avoidsLeavingASingleOrphanSeat() {
        // row A centre block has 4,5 taken: free run is 6-9. Two seats should hug an edge
        // (6-7 or 8-9), never 7-8 which would strand 6 and 9.
        assertEquals("FLOOR-A-6 FLOOR-A-7", allocate(SeatRequest.of(2), "FLOOR-A-4", "FLOOR-A-5"));
        // three premium seats in the run 6-9 always strand one seat, so row B is better
        assertEquals("FLOOR-B-4 FLOOR-B-5 FLOOR-B-6",
                allocate(SeatRequest.of(3).withCategory("PREM"), "FLOOR-A-4", "FLOOR-A-5"));
        // without a category the untouched side block of row A is an exact fit
        assertEquals("FLOOR-A-1 FLOOR-A-2 FLOOR-A-3", allocate(SeatRequest.of(3), "FLOOR-A-4", "FLOOR-A-5"));
    }

    @Test
    void acceptsAnOrphanWhenEveryOptionCreatesOne() {
        Set<SeatId> taken = occupied();
        VENUE.seats().stream().map(Seat::id)
                .filter(id -> !(id.section().equals("FLOOR") && id.row().equals("A") && id.number() >= 10))
                .forEach(taken::add);
        assertEquals("FLOOR-A-10 FLOOR-A-11",
                allocator.allocate(VENUE, taken, SeatRequest.of(2)).orElseThrow().stream()
                        .map(s -> s.id().toString()).collect(Collectors.joining(" ")));
    }

    @Test
    void filtersByCategory() {
        // two seats in a three-seat side block would strand one, so the six-seat block of row C wins
        assertEquals("FLOOR-C-6 FLOOR-C-7", allocate(SeatRequest.of(2).withCategory("STD")));
        assertEquals("FLOOR-A-1 FLOOR-A-2 FLOOR-A-3", allocate(SeatRequest.of(3).withCategory("STD")));
        assertEquals("FLOOR-A-6", allocate(SeatRequest.of(1).withCategory("PREM")));
        assertEquals("none", allocate(SeatRequest.of(7).withCategory("PREM")));
        assertEquals("none", allocate(SeatRequest.of(1).withCategory("VIP")));
    }

    @Test
    void honoursSectionPreferenceWithFallback() {
        assertEquals("BALC-A-4 BALC-A-5", allocate(SeatRequest.of(2).preferringSection("BALC")));
        Set<SeatId> balconyFull = VENUE.seats().stream().map(Seat::id)
                .filter(id -> id.section().equals("BALC")).collect(Collectors.toSet());
        assertEquals("FLOOR-A-6 FLOOR-A-7", allocator.allocate(VENUE, balconyFull,
                SeatRequest.of(2).preferringSection("BALC")).orElseThrow().stream()
                .map(s -> s.id().toString()).collect(Collectors.joining(" ")));
        assertTrue(allocator.allocate(VENUE, balconyFull, SeatRequest.of(2).onlyInSection("BALC")).isEmpty());
        assertEquals("none", allocate(SeatRequest.of(2).onlyInSection("NOPE")));
    }

    @Test
    void keepsAccessibleSeatsForThoseWhoNeedThem() {
        // row C is the only free row; STD seats 3 and 10 are regular, 1-2 and 11-12 accessible
        Set<SeatId> taken = VENUE.seats().stream().map(Seat::id)
                .filter(id -> !(id.section().equals("FLOOR") && id.row().equals("C")))
                .collect(Collectors.toCollection(HashSet::new));
        for (int n = 4; n <= 9; n++) {
            taken.add(new SeatId("FLOOR", "C", n));
        }
        assertEquals("FLOOR-C-3", ids(allocator.allocate(VENUE, taken, SeatRequest.of(1))));
        assertEquals("FLOOR-C-2 FLOOR-C-3", ids(allocator.allocate(VENUE, taken, SeatRequest.of(2))));
        assertEquals("FLOOR-C-1 FLOOR-C-2",
                ids(allocator.allocate(VENUE, taken, SeatRequest.of(2).withAccessibleSeats(2))));
        assertEquals("FLOOR-C-2 FLOOR-C-3",
                ids(allocator.allocate(VENUE, taken, SeatRequest.of(2).withAccessibleSeats(1))));
        assertEquals("none", ids(allocator.allocate(VENUE, taken, SeatRequest.of(3).withAccessibleSeats(3))));
    }

    @Test
    void accessibleRequestFindsTheAccessibleRowEvenWhenFrontRowsAreFree() {
        assertEquals("FLOOR-C-2 FLOOR-C-3", allocate(SeatRequest.of(2).withAccessibleSeats(1)));
    }

    @Test
    void splitsAPartyOnlyWhenAllowed() {
        assertEquals("none", allocate(SeatRequest.of(9).onlyInSection("BALC")));
        assertEquals("BALC-A-1 BALC-A-2 BALC-A-3 BALC-A-4 BALC-A-5 BALC-A-6 BALC-A-7 BALC-B-4 BALC-B-5",
                allocate(SeatRequest.of(9).onlyInSection("BALC").allowingSplit()));
    }

    @Test
    void splitFallsThroughToASectionWithEnoughSeats() {
        Set<SeatId> taken = VENUE.seats().stream().map(Seat::id)
                .filter(id -> id.section().equals("FLOOR") && !id.row().equals("A"))
                .collect(Collectors.toCollection(HashSet::new));
        // FLOOR has 12 free seats in row A only: 3 + 6 + 3
        assertEquals("FLOOR-A-1 FLOOR-A-2 FLOOR-A-3 FLOOR-A-4 FLOOR-A-5 FLOOR-A-6 FLOOR-A-7 FLOOR-A-8 FLOOR-A-9 "
                + "FLOOR-A-10 FLOOR-A-11 FLOOR-A-12",
                ids(allocator.allocate(VENUE, taken, SeatRequest.of(12).allowingSplit())));
        assertEquals("none", ids(allocator.allocate(VENUE, taken, SeatRequest.of(17).allowingSplit())));
    }

    @Test
    void requestValidation() {
        assertThrows(ValidationException.class, () -> SeatRequest.of(0));
        assertThrows(ValidationException.class, () -> SeatRequest.of(2).withAccessibleSeats(3));
        assertThrows(ValidationException.class, () -> SeatRequest.of(2).withAccessibleSeats(-1));
        assertThrows(ValidationException.class, () -> SeatRequest.of(2).allowingSplit().withAccessibleSeats(1));
        assertThrows(ValidationException.class, () -> new SeatRequest(2, null, null, true, 0, false));
    }

    private static String ids(Optional<List<Seat>> seats) {
        return seats.map(list -> list.stream().map(s -> s.id().toString()).collect(Collectors.joining(" ")))
                .orElse("none");
    }
}
