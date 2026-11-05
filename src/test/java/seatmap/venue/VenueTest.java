package seatmap.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import seatmap.error.ValidationException;

class VenueTest {

    private static Row row(String section, String label, int... blockSizes) {
        List<Seat> seats = new ArrayList<>();
        int position = 0;
        for (int block = 0; block < blockSizes.length; block++) {
            for (int i = 0; i < blockSizes[block]; i++) {
                seats.add(new Seat(new SeatId(section, label, position + 1), "STD", false, position, block));
                position++;
            }
        }
        return new Row(section, label, seats);
    }

    @Test
    void indexesSeatsAndCountsCapacity() {
        Section floor = new Section("FLOOR", "Floor", List.of(row("FLOOR", "A", 4), row("FLOOR", "B", 3, 3)));
        Venue venue = new Venue("Hall", List.of(new SeatCategory("STD", "Standard")), List.of(floor));

        assertEquals(10, venue.capacity());
        assertEquals(10, floor.capacity());
        assertTrue(venue.hasSeat(SeatId.parse("FLOOR-B-6")));
        assertFalse(venue.hasSeat(SeatId.parse("FLOOR-B-7")));
        assertEquals("STD", venue.seat(SeatId.parse("FLOOR-A-1")).orElseThrow().category());
        assertEquals("FLOOR-A-1", venue.seats().get(0).id().toString());
        assertTrue(venue.section("FLOOR").isPresent());
        assertTrue(venue.section("NOPE").isEmpty());
    }

    @Test
    void adjacencyStopsAtAisles() {
        Row row = row("FLOOR", "B", 3, 3);
        Seat third = row.seats().get(2);
        Seat fourth = row.seats().get(3);
        assertTrue(row.seats().get(1).isAdjacentTo(third));
        assertFalse(third.isAdjacentTo(fourth));
        assertEquals(2, row.blocks().size());
        assertEquals(3, row.blocks().get(1).size());
    }

    @Test
    void rejectsDuplicateSeatNumbersInARow() {
        List<Seat> seats = List.of(
                new Seat(new SeatId("S", "A", 1), "STD", false, 0, 0),
                new Seat(new SeatId("S", "A", 1), "STD", false, 1, 0));
        assertThrows(ValidationException.class, () -> new Row("S", "A", seats));
    }

    @Test
    void rejectsUndeclaredCategoriesAndDuplicateSections() {
        Section a = new Section("A", "A", List.of(row("A", "1", 2)));
        assertThrows(ValidationException.class,
                () -> new Venue("Hall", List.of(new SeatCategory("VIP", "VIP")), List.of(a)));
        assertThrows(ValidationException.class,
                () -> new Venue("Hall", List.of(new SeatCategory("STD", "Standard")), List.of(a, a)));
    }

    @Test
    void rejectsEmptyStructures() {
        assertThrows(ValidationException.class, () -> new Row("S", "A", List.of()));
        assertThrows(ValidationException.class, () -> new Section("S", "S", List.of()));
        assertThrows(ValidationException.class,
                () -> new Venue(" ", List.of(), List.of(new Section("S", "S", List.of(row("S", "A", 1))))));
    }
}
