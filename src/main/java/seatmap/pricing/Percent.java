package seatmap.pricing;

import seatmap.error.ValidationException;

/** A share between 0 % and 100 %, stored in basis points (hundredths of a percent). */
public record Percent(int basisPoints) {

    public Percent {
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new ValidationException("a percentage must be between 0% and 100%");
        }
    }

    public static Percent of(int wholePercent) {
        return new Percent(Math.multiplyExact(wholePercent, 100));
    }

    /** Parses {@code 15%} or {@code 12.5%}; up to two decimals are allowed. */
    public static Percent parse(String text) {
        if (text == null || !text.endsWith("%") || text.length() < 2) {
            throw new ValidationException("'" + text + "' is not a percentage (expected e.g. 15%)");
        }
        Money scaled;
        try {
            scaled = Money.parse(text.substring(0, text.length() - 1));
        } catch (ValidationException e) {
            throw new ValidationException("'" + text + "' is not a percentage (expected e.g. 15%)");
        }
        if (scaled.cents() > 10_000) {
            throw new ValidationException("a percentage must be between 0% and 100%, got " + text);
        }
        return new Percent((int) scaled.cents());
    }

    @Override
    public String toString() {
        int whole = basisPoints / 100;
        int fraction = basisPoints % 100;
        if (fraction == 0) {
            return whole + "%";
        }
        String digits = fraction % 10 == 0 ? Integer.toString(fraction / 10) : (fraction < 10 ? "0" : "") + fraction;
        return whole + "." + digits + "%";
    }
}
