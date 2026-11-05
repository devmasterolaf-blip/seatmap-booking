package seatmap.pricing;

import java.time.Instant;
import java.util.Locale;

import seatmap.error.PromoCodeException;
import seatmap.error.ValidationException;
import seatmap.venue.Identifiers;

/**
 * A code a customer can enter at confirmation for either a percentage or a fixed amount
 * off. Codes are case-insensitive and valid in the half-open window
 * {@code [validFrom, validUntil)}.
 */
public final class PromoCode {
    /** Value of {@link #maxUses()} for a code that can be redeemed any number of times. */
    public static final int UNLIMITED = 0;

    private final String code;
    private final Percent percent;
    private final Money amount;
    private final Instant validFrom;
    private final Instant validUntil;
    private final int maxUses;

    private PromoCode(String code, Percent percent, Money amount, Instant validFrom, Instant validUntil,
            int maxUses) {
        this.code = normalize(code);
        if (validFrom == null || validUntil == null) {
            throw new ValidationException("promo code " + this.code + " needs a validity window");
        }
        if (!validFrom.isBefore(validUntil)) {
            throw new ValidationException("promo code " + this.code + " ends before it starts");
        }
        if (maxUses < 0) {
            throw new ValidationException("promo code " + this.code + " has a negative use limit");
        }
        this.percent = percent;
        this.amount = amount;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.maxUses = maxUses;
    }

    public static PromoCode percentOff(String code, Percent percent, Instant validFrom, Instant validUntil,
            int maxUses) {
        if (percent == null || percent.basisPoints() == 0) {
            throw new ValidationException("promo code " + code + " needs a non-zero percentage");
        }
        return new PromoCode(code, percent, null, validFrom, validUntil, maxUses);
    }

    public static PromoCode amountOff(String code, Money amount, Instant validFrom, Instant validUntil,
            int maxUses) {
        if (amount == null || amount.isZero()) {
            throw new ValidationException("promo code " + code + " needs a non-zero amount");
        }
        return new PromoCode(code, null, amount, validFrom, validUntil, maxUses);
    }

    /** Upper-cases a code so lookups ignore case. */
    public static String normalize(String code) {
        if (code == null) {
            throw new ValidationException("promo code must not be null");
        }
        return Identifiers.requireCode(code.trim(), "promo code").toUpperCase(Locale.ROOT);
    }

    public String code() {
        return code;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validUntil() {
        return validUntil;
    }

    public int maxUses() {
        return maxUses;
    }

    public boolean isPercentage() {
        return percent != null;
    }

    /** The reduction this code gives on {@code due}; never more than {@code due} itself. */
    public Money discountOn(Money due) {
        return percent != null ? due.percent(percent) : amount.min(due);
    }

    /**
     * Verifies that the code can be redeemed now.
     *
     * @param usesSoFar number of live bookings that already redeemed the code
     * @throws PromoCodeException when the window has not opened, has closed, or the use
     *                            limit is reached
     */
    public void checkRedeemable(Instant now, int usesSoFar) {
        if (now.isBefore(validFrom)) {
            throw new PromoCodeException(code, PromoCodeException.Reason.NOT_YET_VALID,
                    "promo code " + code + " is not valid before " + validFrom);
        }
        if (!now.isBefore(validUntil)) {
            throw new PromoCodeException(code, PromoCodeException.Reason.EXPIRED,
                    "promo code " + code + " expired at " + validUntil);
        }
        if (maxUses != UNLIMITED && usesSoFar >= maxUses) {
            throw new PromoCodeException(code, PromoCodeException.Reason.EXHAUSTED,
                    "promo code " + code + " has been used the maximum of " + maxUses + " times");
        }
    }

    public String describe() {
        String value = percent != null ? percent.toString() : amount.toString();
        String limit = maxUses == UNLIMITED ? "" : ", max " + maxUses + " uses";
        return code + " " + value + " off, " + validFrom + " to " + validUntil + limit;
    }
}
