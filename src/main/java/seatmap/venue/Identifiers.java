package seatmap.venue;

import seatmap.error.ValidationException;

/** Shared validation for the short codes used in seat ids, categories and events. */
public final class Identifiers {
    private Identifiers() {
    }

    /**
     * Checks that {@code value} is a non-empty code made of ASCII letters, digits and
     * underscores. Dashes are excluded because they separate the parts of a seat id.
     */
    public static String requireCode(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new ValidationException(what + " must not be empty");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                throw new ValidationException(
                        what + " '" + value + "' may only contain letters, digits and underscores");
            }
        }
        return value;
    }

    public static boolean isCode(String value) {
        try {
            requireCode(value, "code");
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }
}
