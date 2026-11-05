package seatmap.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import seatmap.error.ConfigParseException;

class VenueLayoutParserTest {

    private static final String LAYOUT = """
            # sample layout
            venue "Orpheum Theatre"
            category PREM "Premium"
            category STD

            section ORCH "Orchestra"
              row A  1-2:STD+acc 3-4:STD | 5-8:PREM | 9-10:STD
              row B  10-1:STD
            section BALC
              row AA 1:STD 3:STD 5:STD
            """;

    private static ConfigParseException fails(String layout) {
        return assertThrows(ConfigParseException.class, () -> VenueLayoutParser.parse(layout, "bad.layout"));
    }

    @Test
    void parsesSectionsRowsAndCategories() {
        Venue venue = VenueLayoutParser.parse(LAYOUT, "orpheum.layout");
        assertEquals("Orpheum Theatre", venue.name());
        assertEquals(List.of("PREM", "STD"), List.copyOf(venue.categories().keySet()));
        assertEquals("STD", venue.categories().get("STD").displayName());
        assertEquals(23, venue.capacity());
        assertEquals("Orchestra", venue.section("ORCH").orElseThrow().name());
        assertEquals("BALC", venue.section("BALC").orElseThrow().name());
    }

    @Test
    void assignsBlocksPositionsAndFlags() {
        Venue venue = VenueLayoutParser.parse(LAYOUT, "orpheum.layout");
        Seat a2 = venue.seat(SeatId.parse("ORCH-A-2")).orElseThrow();
        Seat a4 = venue.seat(SeatId.parse("ORCH-A-4")).orElseThrow();
        Seat a5 = venue.seat(SeatId.parse("ORCH-A-5")).orElseThrow();
        Seat a9 = venue.seat(SeatId.parse("ORCH-A-9")).orElseThrow();
        assertTrue(a2.accessible());
        assertFalse(a4.accessible());
        assertEquals(0, a4.block());
        assertEquals(1, a5.block());
        assertEquals("PREM", a5.category());
        assertEquals(2, a9.block());
        assertEquals(8, a9.position());
        assertFalse(a4.isAdjacentTo(a5));
    }

    @Test
    void descendingRangeNumbersSeatsRightToLeft() {
        Venue venue = VenueLayoutParser.parse(LAYOUT, "orpheum.layout");
        Row b = venue.section("ORCH").orElseThrow().row("B").orElseThrow();
        assertEquals(10, b.seats().get(0).id().number());
        assertEquals(1, b.seats().get(9).id().number());
        assertEquals(9, venue.seat(SeatId.parse("ORCH-B-1")).orElseThrow().position());
    }

    @Test
    void reportsLineNumbersForStructuralErrors() {
        assertEquals(2, fails("venue \"X\"\nrow A 1-2:STD\n").line());
        assertEquals(3, fails("venue \"X\"\ncategory STD\ncategory STD\n").line());
        assertEquals(1, fails("theatre \"X\"\n").line());
        ConfigParseException noRows = fails("venue \"X\"\ncategory STD\nsection A\nsection B\n row 1 1:STD\n");
        assertEquals(3, noRows.line());
        assertTrue(noRows.getMessage().contains("no rows"));
        assertTrue(fails("category STD\nsection A\n row 1 1:STD\n").getMessage().contains("missing 'venue"));
        assertTrue(fails("venue \"X\"\ncategory STD\n").getMessage().contains("no sections"));
    }

    @Test
    void rejectsBadSeatGroups() {
        String head = "venue \"X\"\ncategory STD\nsection A\n";
        assertTrue(fails(head + "row 1 1-4:VIP\n").getMessage().contains("undeclared category 'VIP'"));
        assertTrue(fails(head + "row 1 1-4\n").getMessage().contains("must look like"));
        assertTrue(fails(head + "row 1 a-4:STD\n").getMessage().contains("invalid seat number"));
        assertTrue(fails(head + "row 1 0-4:STD\n").getMessage().contains("start at 1"));
        assertTrue(fails(head + "row 1 1-4:STD 4-6:STD\n").getMessage().contains("appears twice"));
        assertTrue(fails(head + "row 1 1-4:STD+vip\n").getMessage().contains("unknown seat flag"));
        assertTrue(fails(head + "row 1 1-9999:STD\n").getMessage().contains("more than"));
    }

    @Test
    void rejectsMisplacedAisles() {
        String head = "venue \"X\"\ncategory STD\nsection A\n";
        assertThrows(ConfigParseException.class, () -> VenueLayoutParser.parse(head + "row 1 | 1-4:STD\n", "x"));
        assertThrows(ConfigParseException.class, () -> VenueLayoutParser.parse(head + "row 1 1-4:STD |\n", "x"));
        assertThrows(ConfigParseException.class,
                () -> VenueLayoutParser.parse(head + "row 1 1-2:STD | | 3-4:STD\n", "x"));
    }

    @Test
    void rejectsDuplicateRowsAndSections() {
        String head = "venue \"X\"\ncategory STD\nsection A\n row 1 1:STD\n";
        assertEquals(5, fails(head + " row 1 2:STD\n").line());
        assertEquals(5, fails(head + "section A\n row 2 1:STD\n").line());
    }
}
