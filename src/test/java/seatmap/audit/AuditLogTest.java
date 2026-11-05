package seatmap.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import seatmap.audit.AuditEntry.Type;
import seatmap.error.StorageException;
import seatmap.error.ValidationException;
import seatmap.text.Fields;

class AuditLogTest {
    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void fieldsRoundTripSpecialCharacters() {
        List<String> fields = List.of("plain", "tab\there", "line\nbreak\r", "back\\slash", "", "trailing\\");
        String line = Fields.join(fields);
        assertEquals(1, line.lines().count());
        assertEquals(fields, Fields.split(line));
        assertEquals(List.of(""), Fields.split(""));
        assertEquals(List.of("", ""), Fields.split("\t"));
        assertThrows(ValidationException.class, () -> Fields.split("bad\\"));
        assertThrows(ValidationException.class, () -> Fields.split("bad\\x"));
    }

    @Test
    void inMemoryLogKeepsOrder() {
        AuditLog log = new InMemoryAuditLog();
        log.record(new AuditEntry(T0, Type.HOLD_PLACED, "E", "H-1", "Ann"));
        log.record(new AuditEntry(T0.plusSeconds(1), Type.HOLD_RELEASED, "E", "H-1", null));
        assertEquals(List.of(Type.HOLD_PLACED, Type.HOLD_RELEASED), log.entries().stream().map(AuditEntry::type).toList());
        assertEquals("", log.entries().get(1).detail());
        assertTrue(AuditLog.NONE.entries().isEmpty());
    }

    @Test
    void fileLogAppendsAndReadsBack(@TempDir Path dir) {
        Path file = dir.resolve("audit.log");
        FileAuditLog log = new FileAuditLog(file);
        assertTrue(log.entries().isEmpty());
        AuditEntry first = new AuditEntry(T0, Type.BOOKING_CONFIRMED, "E", "B-1", "customer=Ann\tO'Neil total=72.00");
        AuditEntry second = new AuditEntry(T0.plusSeconds(5), Type.BOOKING_CANCELLED, "E", "B-1", "refund=36.00");
        log.record(first);
        log.record(second);
        assertEquals(List.of(first, second), new FileAuditLog(file).entries());
    }

    @Test
    void fileLogReportsCorruptLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("audit.log");
        Files.writeString(file, "2026-10-01T12:00:00Z\tHOLD_PLACED\tE\tH-1\tok\nnot-a-time\tHOLD_PLACED\tE\tH-2\tx\n");
        StorageException e = assertThrows(StorageException.class, () -> new FileAuditLog(file).entries());
        assertTrue(e.getMessage().contains("line 2"));
        Files.writeString(file, "2026-10-01T12:00:00Z\tHOLD_PLACED\tE\n");
        assertTrue(assertThrows(StorageException.class, () -> new FileAuditLog(file).entries())
                .getMessage().contains("expected 5 fields"));
        Files.writeString(file, "2026-10-01T12:00:00Z\tHOLD_STOLEN\tE\tH-1\tx\n");
        assertThrows(StorageException.class, () -> new FileAuditLog(file).entries());
    }
}
