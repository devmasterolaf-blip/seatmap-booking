package seatmap.venue;

/** A priced class of seats such as "Premium" or "Balcony". */
public record SeatCategory(String code, String displayName) {

    public SeatCategory {
        Identifiers.requireCode(code, "category code");
        if (displayName == null || displayName.isBlank()) {
            displayName = code;
        }
    }
}
