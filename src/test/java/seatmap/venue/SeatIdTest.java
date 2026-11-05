package seatmap.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import seatmap.error.ValidationException;

class SeatIdTest {

    @Test
    void roundTripsThroughText() {
        SeatId id = SeatId.parse("ORCH-B-12");
        assertEquals("ORCH", id.section());
        assertEquals("B", id.row());
        assertEquals(12, id.number());
        assertEquals("ORCH-B-12", id.toString());
        assertEquals(id, SeatId.parse(" ORCH-B-12 "));
    }

    @Test
    void rejectsMalformedIds() {
        assertThrows(ValidationException.class, () -> SeatId.parse("ORCH-B"));
        assertThrows(ValidationException.class, () -> SeatId.parse("ORCH-B-x"));
        assertThrows(ValidationException.class, () -> SeatId.parse("ORCH-B-1-2"));
        assertThrows(ValidationException.class, () -> SeatId.parse("-B-1"));
        assertThrows(ValidationException.class, () -> SeatId.parse("ORCH-B-0"));
        assertThrows(ValidationException.class, () -> SeatId.parse(null));
        assertThrows(ValidationException.class, () -> new SeatId("OR CH", "B", 1));
    }

    @Test
    void sortsBySectionRowThenNumericSeat() {
        List<SeatId> ids = new ArrayList<>(List.of(
                SeatId.parse("B-A-10"), SeatId.parse("A-B-2"), SeatId.parse("A-A-10"), SeatId.parse("A-A-9")));
        Collections.sort(ids);
        assertEquals(List.of("A-A-9", "A-A-10", "A-B-2", "B-A-10"),
                ids.stream().map(SeatId::toString).toList());
    }

    @Test
    void identifiersAcceptOnlyPlainCodes() {
        assertTrue(Identifiers.isCode("VIP_2"));
        assertTrue(!Identifiers.isCode("VIP-2"));
        assertTrue(!Identifiers.isCode(""));
    }
}
