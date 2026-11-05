package seatmap.cli;

import java.util.List;
import java.util.Map;

import seatmap.booking.SeatStatus;
import seatmap.venue.Row;
import seatmap.venue.Seat;
import seatmap.venue.SeatId;
import seatmap.venue.Section;

/**
 * Draws a section as text, one line per row and one character per seat:
 * {@code o} free, {@code a} free accessible seat, {@code h} held, {@code x} booked.
 * Aisles are shown as a gap.
 */
final class SeatMapRenderer {
    static final String LEGEND = "o free  a free (accessible)  h held  x booked";

    private SeatMapRenderer() {
    }

    static String render(Section section, Map<SeatId, SeatStatus> statuses) {
        int labelWidth = section.rows().stream().mapToInt(row -> row.label().length()).max().orElse(1);
        StringBuilder text = new StringBuilder();
        for (Row row : section.rows()) {
            text.append("  ").append(" ".repeat(labelWidth - row.label().length())).append(row.label()).append("  ");
            List<List<Seat>> blocks = row.blocks();
            for (int b = 0; b < blocks.size(); b++) {
                if (b > 0) {
                    text.append("  ");
                }
                for (Seat seat : blocks.get(b)) {
                    text.append(symbol(seat, statuses.getOrDefault(seat.id(), SeatStatus.FREE)));
                }
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static char symbol(Seat seat, SeatStatus status) {
        return switch (status) {
            case BOOKED -> 'x';
            case HELD -> 'h';
            case FREE -> seat.accessible() ? 'a' : 'o';
        };
    }
}
