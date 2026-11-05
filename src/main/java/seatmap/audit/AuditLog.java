package seatmap.audit;

import java.util.List;

/** Append-only record of everything the reservation service did. */
public interface AuditLog {

    /** A log that drops every entry. */
    AuditLog NONE = new AuditLog() {
        @Override
        public void record(AuditEntry entry) {
        }

        @Override
        public List<AuditEntry> entries() {
            return List.of();
        }
    };

    void record(AuditEntry entry);

    /** All entries in the order they were recorded. */
    List<AuditEntry> entries();
}
