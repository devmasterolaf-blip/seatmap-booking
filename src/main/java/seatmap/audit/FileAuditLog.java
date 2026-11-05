package seatmap.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import seatmap.error.StorageException;
import seatmap.error.ValidationException;
import seatmap.text.Fields;

/**
 * Appends each entry as one tab-separated line to a file:
 * {@code time, type, event, subject, detail}.
 */
public final class FileAuditLog implements AuditLog {
    private final Path file;

    public FileAuditLog(Path file) {
        this.file = file;
    }

    @Override
    public synchronized void record(AuditEntry entry) {
        String line = Fields.join(List.of(entry.at().toString(), entry.type().name(), entry.eventId(),
                entry.subject(), entry.detail())) + "\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException("cannot write audit log " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<AuditEntry> entries() {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("cannot read audit log " + file + ": " + e.getMessage(), e);
        }
        List<AuditEntry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) {
                continue;
            }
            try {
                List<String> fields = Fields.split(lines.get(i));
                if (fields.size() != 5) {
                    throw new ValidationException("expected 5 fields, found " + fields.size());
                }
                entries.add(new AuditEntry(Instant.parse(fields.get(0)), AuditEntry.Type.valueOf(fields.get(1)),
                        fields.get(2), fields.get(3), fields.get(4)));
            } catch (ValidationException | DateTimeParseException | IllegalArgumentException e) {
                throw new StorageException("audit log " + file + " is corrupt at line " + (i + 1) + ": "
                        + e.getMessage(), e);
            }
        }
        return entries;
    }
}
