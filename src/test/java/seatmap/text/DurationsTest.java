package seatmap.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import seatmap.error.ValidationException;

class DurationsTest {

    @ParameterizedTest
    @CsvSource({ "90s, 90", "15m, 900", "24h, 86400", "7d, 604800", "0m, 0", "007m, 420" })
    void parsesAmountAndUnit(String text, long seconds) {
        assertEquals(Duration.ofSeconds(seconds), Durations.parse(text));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "m", "15", "-5m", "+5m", "1.5h", "15 m", "m15", "fifteen minutes" })
    void rejectsMalformedText(String text) {
        ValidationException e = assertThrows(ValidationException.class, () -> Durations.parse(text));
        assertTrue(e.getMessage().contains("'" + text + "'"), e.getMessage());
    }

    @Test
    void rejectsNullAndUnknownUnits() {
        assertThrows(ValidationException.class, () -> Durations.parse(null));
        ValidationException e = assertThrows(ValidationException.class, () -> Durations.parse("3w"));
        assertEquals("'3w' has an unknown duration unit 'w' (use s, m, h or d)", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = { "9223372036854775807m", "999999999999999d", "9223372036854775807h" })
    void rejectsAmountsTooLargeToRepresent(String text) {
        ValidationException e = assertThrows(ValidationException.class, () -> Durations.parse(text));
        assertEquals("'" + text + "' is too long a duration", e.getMessage());
    }

    @Test
    void acceptsTheLargestNumberOfSeconds() {
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE), Durations.parse("9223372036854775807s"));
    }

    @ParameterizedTest
    @CsvSource({ "0, 0s", "59, 59s", "60, 1m", "90, 90s", "3600, 1h", "5400, 90m", "86400, 1d", "129600, 36h",
            "604800, 7d" })
    void formatsWithTheLargestExactUnit(long seconds, String expected) {
        assertEquals(expected, Durations.format(Duration.ofSeconds(seconds)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "45s", "10m", "36h", "30d" })
    void formatReversesParse(String text) {
        assertEquals(text, Durations.format(Durations.parse(text)));
    }
}
