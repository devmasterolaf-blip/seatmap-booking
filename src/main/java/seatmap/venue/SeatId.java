package seatmap.venue;

import java.util.Comparator;

import seatmap.error.ValidationException;

/**
 * Address of a seat inside a venue: section code, row label and seat number, written as
 * {@code SECTION-ROW-NUMBER}, for example {@code ORCH-B-12}.
 */
public record SeatId(String section, String row, int number) implements Comparable<SeatId> {

    private static final Comparator<SeatId> ORDER = Comparator
            .comparing(SeatId::section)
            .thenComparing(SeatId::row)
            .thenComparingInt(SeatId::number);

    public SeatId {
        Identifiers.requireCode(section, "section code");
        Identifiers.requireCode(row, "row label");
        if (number < 1) {
            throw new ValidationException("seat number must be positive, got " + number);
        }
    }

    /** Parses the {@code SECTION-ROW-NUMBER} form produced by {@link #toString()}. */
    public static SeatId parse(String text) {
        if (text == null) {
            throw new ValidationException("seat id must not be null");
        }
        String[] parts = text.trim().split("-", -1);
        if (parts.length != 3) {
            throw new ValidationException(
                    "seat id '" + text + "' must have the form SECTION-ROW-NUMBER");
        }
        int number;
        try {
            number = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new ValidationException("seat id '" + text + "' has a non-numeric seat number");
        }
        return new SeatId(parts[0], parts[1], number);
    }

    @Override
    public int compareTo(SeatId other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return section + "-" + row + "-" + number;
    }
}
