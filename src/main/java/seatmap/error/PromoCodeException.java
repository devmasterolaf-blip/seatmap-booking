package seatmap.error;

/** A promo code was rejected; {@link #reason()} says why. */
public class PromoCodeException extends SeatmapException {
    private static final long serialVersionUID = 1L;

    /** Why a code could not be redeemed. */
    public enum Reason { UNKNOWN, NOT_YET_VALID, EXPIRED, EXHAUSTED }

    private final String code;
    private final Reason reason;

    public PromoCodeException(String code, Reason reason, String message) {
        super(message);
        this.code = code;
        this.reason = reason;
    }

    public String code() {
        return code;
    }

    public Reason reason() {
        return reason;
    }
}
