package seatmap.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import seatmap.error.ValidationException;

class FieldsTest {

    @Test
    void joinsWithTabs() {
        assertEquals("hold\tH-000001\tAnn Lee", Fields.join(List.of("hold", "H-000001", "Ann Lee")));
    }

    @Test
    void escapesSeparatorsAndLineBreaks() {
        String line = Fields.join(List.of("a\tb", "line1\nline2\r", "back\\slash"));

        assertEquals("a\\tb\tline1\\nline2\\r\tback\\\\slash", line);
        assertFalse(line.contains("\n"));
        assertEquals(List.of("a\tb", "line1\nline2\r", "back\\slash"), Fields.split(line));
    }

    @Test
    void keepsEmptyFieldsIncludingTrailingOnes() {
        List<String> fields = List.of("", "x", "", "");

        assertEquals("\tx\t\t", Fields.join(fields));
        assertEquals(fields, Fields.split("\tx\t\t"));
        assertEquals(List.of(""), Fields.split(""));
    }

    @Test
    void aBackslashFollowedByALetterIsNotMistakenForAnEscape() {
        List<String> fields = List.of("C:\\temp\\new", "\\t");

        assertEquals(fields, Fields.split(Fields.join(fields)));
    }

    @Test
    void rejectsBrokenEscapes() {
        assertEquals("record ends in the middle of an escape",
                assertThrows(ValidationException.class, () -> Fields.split("abc\\")).getMessage());
        assertEquals("record has an unknown escape \\q",
                assertThrows(ValidationException.class, () -> Fields.split("a\\qb")).getMessage());
    }
}
