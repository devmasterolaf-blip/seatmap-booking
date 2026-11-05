package seatmap.alloc;

import seatmap.error.ValidationException;

/**
 * What a customer asks the allocator for.
 *
 * @param count           number of seats wanted
 * @param category        only seats of this category, or {@code null} for any
 * @param section         section to try first, or {@code null} for venue order
 * @param sectionOnly     when true, never leave the requested section
 * @param accessibleSeats how many of the seats must be accessible positions
 * @param allowSplit      whether the party may be split into several blocks when no
 *                        single block of adjacent seats is free
 */
public record SeatRequest(int count, String category, String section, boolean sectionOnly, int accessibleSeats,
        boolean allowSplit) {

    public SeatRequest {
        if (count < 1) {
            throw new ValidationException("the number of seats must be at least 1, got " + count);
        }
        if (accessibleSeats < 0 || accessibleSeats > count) {
            throw new ValidationException("accessible seats must be between 0 and the party size");
        }
        if (sectionOnly && section == null) {
            throw new ValidationException("a section must be named to restrict the search to it");
        }
        if (allowSplit && accessibleSeats > 0) {
            throw new ValidationException("a split party cannot be combined with accessible seats");
        }
    }

    public static SeatRequest of(int count) {
        return new SeatRequest(count, null, null, false, 0, false);
    }

    public SeatRequest withCategory(String newCategory) {
        return new SeatRequest(count, newCategory, section, sectionOnly, accessibleSeats, allowSplit);
    }

    /** Prefers a section but falls back to the others when it has no fitting seats. */
    public SeatRequest preferringSection(String newSection) {
        return new SeatRequest(count, category, newSection, false, accessibleSeats, allowSplit);
    }

    public SeatRequest onlyInSection(String newSection) {
        return new SeatRequest(count, category, newSection, true, accessibleSeats, allowSplit);
    }

    public SeatRequest withAccessibleSeats(int newAccessibleSeats) {
        return new SeatRequest(count, category, section, sectionOnly, newAccessibleSeats, allowSplit);
    }

    public SeatRequest allowingSplit() {
        return new SeatRequest(count, category, section, sectionOnly, accessibleSeats, true);
    }
}
