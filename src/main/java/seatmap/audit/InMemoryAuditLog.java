package seatmap.audit;

import java.util.ArrayList;
import java.util.List;

/** Keeps the trail in a list. */
public final class InMemoryAuditLog implements AuditLog {
    private final List<AuditEntry> entries = new ArrayList<>();

    @Override
    public synchronized void record(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public synchronized List<AuditEntry> entries() {
        return List.copyOf(entries);
    }
}
