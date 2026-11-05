package seatmap.text;

import java.time.Duration;

import seatmap.error.ValidationException;

/** Parses and prints short durations such as {@code 90s}, {@code 15m}, {@code 24h}, {@code 7d}. */
public final class Durations {
    private Durations() {
    }

    public static Duration parse(String text) {
        if (text == null || text.length() < 2) {
            throw new ValidationException("'" + text + "' is not a duration (expected e.g. 15m, 24h, 7d)");
        }
        char unit = text.charAt(text.length() - 1);
        String digits = text.substring(0, text.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new ValidationException("'" + text + "' is not a duration (expected e.g. 15m, 24h, 7d)");
        }
        if (amount < 0 || digits.startsWith("+")) {
            throw new ValidationException("'" + text + "' is not a duration (expected e.g. 15m, 24h, 7d)");
        }
        try {
            return switch (unit) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> throw new ValidationException(
                        "'" + text + "' has an unknown duration unit '" + unit + "' (use s, m, h or d)");
            };
        } catch (ArithmeticException e) {
            throw new ValidationException("'" + text + "' is too long a duration");
        }
    }

    /** Prints a duration with the largest unit that represents it exactly. */
    public static String format(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds != 0 && seconds % 86_400 == 0) {
            return seconds / 86_400 + "d";
        }
        if (seconds != 0 && seconds % 3_600 == 0) {
            return seconds / 3_600 + "h";
        }
        if (seconds != 0 && seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }
}
