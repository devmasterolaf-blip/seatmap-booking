package seatmap.pricing;

import seatmap.error.ValidationException;

/** A non-negative amount in a single currency, stored in minor units (cents). */
public record Money(long cents) implements Comparable<Money> {
    public static final Money ZERO = new Money(0);

    public Money {
        if (cents < 0) {
            throw new ValidationException("an amount must not be negative");
        }
    }

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    /** Parses a plain decimal such as {@code 45}, {@code 45.5} or {@code 45.50}. */
    public static Money parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new ValidationException("an amount must not be empty");
        }
        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        String fraction = dot < 0 ? "" : text.substring(dot + 1);
        if (whole.isEmpty() || !isDigits(whole) || whole.length() > 12
                || (dot >= 0 && (fraction.isEmpty() || fraction.length() > 2 || !isDigits(fraction)))) {
            throw new ValidationException("'" + text + "' is not a valid amount (expected e.g. 45.00)");
        }
        long cents = Long.parseLong(whole) * 100;
        if (!fraction.isEmpty()) {
            cents += Long.parseLong(fraction.length() == 1 ? fraction + "0" : fraction);
        }
        return new Money(cents);
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    /** Subtracts {@code other}, stopping at zero instead of going negative. */
    public Money minusFloored(Money other) {
        return new Money(Math.max(0, cents - other.cents));
    }

    public Money times(int quantity) {
        if (quantity < 0) {
            throw new ValidationException("quantity must not be negative");
        }
        return new Money(Math.multiplyExact(cents, (long) quantity));
    }

    /** The given share of this amount, rounded half up to the nearest cent. */
    public Money percent(Percent percent) {
        long scaled = Math.multiplyExact(cents, (long) percent.basisPoints());
        return new Money((scaled + 5_000) / 10_000);
    }

    public Money min(Money other) {
        return cents <= other.cents ? this : other;
    }

    public boolean isZero() {
        return cents == 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(cents, other.cents);
    }

    @Override
    public String toString() {
        return cents / 100 + "." + (cents % 100 < 10 ? "0" : "") + cents % 100;
    }
}
