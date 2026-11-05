package seatmap.audit;

import java.time.Instant;

import seatmap.error.ValidationException;

/**
 * One line of the audit trail.
 *
 * @param subject the hold or booking id the entry is about
 * @param detail  free-form context such as the customer, seats or amounts
 */
public record AuditEntry(Instant at, Type type, String eventId, String subject, String detail) {

    /** What happened. */
    public enum Type { HOLD_PLACED, HOLD_RELEASED, HOLD_EXPIRED, BOOKING_CONFIRMED, BOOKING_CANCELLED }

    public AuditEntry {
        if (at == null || type == null || eventId == null || subject == null) {
            throw new ValidationException("an audit entry needs a time, type, event and subject");
        }
        if (detail == null) {
            detail = "";
        }
    }
}
