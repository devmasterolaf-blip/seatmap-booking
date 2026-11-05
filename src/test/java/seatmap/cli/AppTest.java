package seatmap.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the command line end to end against a copy of the example data directory. */
class AppTest {
    private static final Path EXAMPLE = Path.of("examples", "orpheum");
    private static final String NOON = "2026-10-01T12:00:00Z";

    @TempDir
    Path data;

    record Result(int code, String out, String err) {
    }

    @BeforeEach
    void copyExampleData() throws IOException {
        for (String file : List.of("events.conf", "orpheum.layout")) {
            Files.copy(EXAMPLE.resolve(file), data.resolve(file));
        }
    }

    /** Runs one command at {@link #NOON}. */
    private Result run(String... command) {
        return runAt(NOON, command);
    }

    private Result runAt(String now, String... command) {
        List<String> argv = new ArrayList<>(List.of("--data", data.toString(), "--now", now));
        argv.addAll(List.of(command));
        return invoke(argv.toArray(String[]::new));
    }

    private static Result invoke(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Clock unused = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        int code = new App(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), unused).run(argv);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void listsEvents() {
        Result result = run("events");

        assertEquals(0, result.code());
        assertEquals("""
                HAMLET   Hamlet            2026-12-01T19:30:00Z  Orpheum Theatre  88 seats
                MATINEE  Hamlet (matinee)  2026-12-05T14:00:00Z  Orpheum Theatre  88 seats
                """, result.out());
        assertEquals("", result.err());
    }

    @Test
    void helpPrintsUsage() {
        Result result = invoke("help");

        assertEquals(0, result.code());
        assertTrue(result.out().startsWith("usage: seatmap"));
        assertEquals(result.out(), invoke("--help").out());
    }

    @Test
    void missingOrUnknownCommandIsAUsageError() {
        Result none = invoke();
        assertEquals(2, none.code());
        assertEquals("", none.out());
        assertTrue(none.err().contains("no command given"));

        Result unknown = run("frobnicate");
        assertEquals(2, unknown.code());
        assertEquals("seatmap: unknown command 'frobnicate'\nRun 'seatmap help' for usage.\n",
                unknown.err().replace("\r\n", "\n"));
    }

    @Test
    void holdConfirmAndCancelAcrossInvocations() {
        Result hold = run("hold", "HAMLET", "--customer", "Ann Lee", "--seats", "ORCH-A-4,ORCH-A-5");
        assertEquals(0, hold.code(), hold.err());
        assertEquals("Held H-000001 for Ann Lee: ORCH-A-4 ORCH-A-5 (expires 2026-10-01T12:15:00Z)",
                hold.out().strip());

        Result confirm = runAt("2026-10-01T12:05:00Z", "confirm", "H-000001", "--promo", "fiveoff");
        assertEquals(0, confirm.code(), confirm.err());
        assertTrue(confirm.out().startsWith("Confirmed B-000001 for Ann Lee: ORCH-A-4 ORCH-A-5"));
        assertTrue(confirm.out().contains("early-bird 15%   -25.50"), confirm.out());
        assertTrue(confirm.out().contains("promo FIVEOFF     -5.00"), confirm.out());
        assertTrue(confirm.out().contains("total            139.50"), confirm.out());

        Result map = run("availability", "HAMLET", "--section", "ORCH", "--map");
        assertTrue(map.out().contains("ORCH     Orchestra        56    54     0       2"), map.out());
        assertTrue(map.out().contains("  A  ooo  xxoooooo  ooo"), map.out());
        assertFalse(map.out().contains("Balcony"));

        Result cancel = run("cancel", "B-000001");
        assertEquals("Cancelled B-000001: refund 139.50 of 139.50", cancel.out().strip());

        Result again = run("cancel", "B-000001");
        assertEquals(1, again.code());
        assertTrue(again.err().contains("B-000001"), again.err());
    }

    @Test
    void holdsBestAvailableSeats() {
        Result first = run("hold", "HAMLET", "--customer", "Bob", "--best", "4", "--category", "PREM");
        assertEquals("Held H-000001 for Bob: ORCH-A-6 ORCH-A-7 ORCH-A-8 ORCH-A-9 (expires 2026-10-01T12:15:00Z)",
                first.out().strip());

        Result balcony = run("hold", "HAMLET", "--customer", "Cy", "--best", "2", "--section", "BALC",
                "--section-only", "--ttl", "30m");
        assertEquals(0, balcony.code(), balcony.err());
        assertTrue(balcony.out().startsWith("Held H-000002 for Cy: BALC-A-"), balcony.out());
        assertTrue(balcony.out().contains("expires 2026-10-01T12:30:00Z"), balcony.out());
    }

    @Test
    void aTakenSeatCannotBeHeldAgain() {
        run("hold", "HAMLET", "--customer", "Ann", "--seats", "ORCH-A-6");

        Result clash = run("hold", "HAMLET", "--customer", "Bob", "--seats", "ORCH-A-5,ORCH-A-6");

        assertEquals(1, clash.code());
        assertEquals("seatmap: seat ORCH-A-6 is not available for event HAMLET", clash.err().strip());
        assertEquals("", clash.out());
    }

    @Test
    void anExpiredHoldCannotBeConfirmed() {
        run("hold", "HAMLET", "--customer", "Ann", "--seats", "ORCH-A-6");

        Result late = runAt("2026-10-01T12:15:00Z", "confirm", "H-000001");

        assertEquals(1, late.code());
        assertEquals("seatmap: hold H-000001 expired at 2026-10-01T12:15:00Z", late.err().strip());
        assertTrue(run("availability", "HAMLET").out().contains("ORCH     Orchestra        56    56     0       0"));
    }

    @Test
    void expireRemovesOverdueHolds() {
        run("hold", "HAMLET", "--customer", "Ann", "--seats", "ORCH-A-6");
        run("hold", "MATINEE", "--customer", "Bob", "--seats", "ORCH-A-6", "--ttl", "2h");

        assertEquals("Removed 0 expired hold(s)", run("expire").out().strip());
        assertEquals("Removed 1 expired hold(s)", runAt("2026-10-01T13:00:00Z", "expire").out().strip());
        assertEquals("Removed 1 expired hold(s)", runAt("2026-10-01T14:00:00Z", "expire").out().strip());
    }

    @Test
    void releaseFreesTheSeats() {
        run("hold", "HAMLET", "--customer", "Ann", "--seats", "ORCH-A-6");

        assertEquals("Released H-000001", run("release", "H-000001").out().strip());
        assertEquals(0, run("hold", "HAMLET", "--customer", "Bob", "--seats", "ORCH-A-6").code());
        assertEquals(1, run("release", "H-000001").code());
    }

    @Test
    void quoteDoesNotReserveAnything() {
        Result quote = run("quote", "HAMLET", "--seats", "ORCH-A-4,BALC-A-1", "--promo", "SPRING10");

        assertEquals(0, quote.code(), quote.err());
        assertEquals("""
                Quote for 2 seat(s) at event HAMLET
                  ORCH-A-4 (PREM)   85.00
                  BALC-A-1 (BALC)   35.00
                  subtotal         120.00
                  early-bird 15%   -18.00
                  promo SPRING10   -10.20
                  total             91.80
                """, quote.out());
        assertFalse(Files.exists(data.resolve(App.AUDIT_FILE)));
    }

    @Test
    void reportShowsOccupancyAndRevenue() {
        run("hold", "HAMLET", "--customer", "Ann", "--seats", "ORCH-A-4,ORCH-A-5");
        run("confirm", "H-000001");
        run("hold", "HAMLET", "--customer", "Bob", "--seats", "BALC-A-1");

        Result report = run("report", "HAMLET");

        assertEquals(0, report.code(), report.err());
        assertTrue(report.out().contains("  ORCH         56       2     0    54       3.6"), report.out());
        assertTrue(report.out().contains("  BALC         32       0     1    31       0.0"), report.out());
        assertTrue(report.out().contains("  TOTAL        88       2     1    85       2.3"), report.out());
        assertTrue(report.out().contains("1 confirmed and 0 cancelled booking(s), 2 seat(s) sold"), report.out());
        assertTrue(report.out().contains("  net                      144.50"), report.out());
    }

    @Test
    void auditTrailCanBeFilteredAndLimited() {
        run("hold", "HAMLET", "--customer", "Ann", "--seats", "ORCH-A-4");
        run("hold", "MATINEE", "--customer", "Bob", "--seats", "ORCH-A-4");
        run("confirm", "H-000001");

        assertEquals(3, run("audit").out().lines().count());
        Result matinee = run("audit", "--event", "MATINEE");
        assertEquals(1, matinee.out().lines().count());
        assertTrue(matinee.out().contains("HOLD_PLACED"), matinee.out());
        Result last = run("audit", "--limit", "1");
        assertTrue(last.out().contains("BOOKING_CONFIRMED  HAMLET  B-000001"), last.out());
        assertEquals(1, last.out().lines().count());
    }

    @Test
    void usageErrorsExitWithTwo() {
        assertEquals(2, run("hold", "HAMLET", "--customer", "Ann").code());
        assertEquals(2, run("hold", "HAMLET", "--customer", "Ann", "--seats", "ORCH-A-1", "--best", "2").code());
        assertEquals(2, run("hold", "HAMLET", "--customer", "Ann", "--seats", "ORCH-A-1", "--split").code());
        assertEquals(2, run("hold", "HAMLET", "--customer", "Ann", "--best", "two").code());
        assertEquals(2, run("hold", "HAMLET", "--best", "2").code());
        assertEquals(2, run("events", "extra").code());
        assertEquals(2, run("events", "--map").code());
        assertEquals(2, run("audit", "--limit", "0").code());
        assertEquals(2, runAt("yesterday", "events").code());
    }

    @Test
    void domainErrorsExitWithOne() {
        assertEquals(1, run("availability", "NOPE").code());
        assertEquals(1, run("availability", "HAMLET", "--section", "LOGE").code());
        assertEquals(1, run("hold", "HAMLET", "--customer", "Ann", "--seats", "banana").code());
        assertEquals(1, run("hold", "HAMLET", "--customer", "Ann", "--best", "2", "--ttl", "soon").code());
        assertEquals(1, run("confirm", "H-000404").code());
        assertEquals(1, runAt("2026-12-02T00:00:00Z", "hold", "HAMLET", "--customer", "Ann", "--best", "2").code());
    }

    @Test
    void aMissingDataDirectoryIsReported() {
        Result result = invoke("--data", data.resolve("nowhere").toString(), "events");

        assertEquals(1, result.code());
        assertTrue(result.err().startsWith("seatmap: no events.conf in data directory"), result.err());
    }
}
