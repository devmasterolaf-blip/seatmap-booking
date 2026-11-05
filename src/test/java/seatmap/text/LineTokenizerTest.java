package seatmap.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import seatmap.error.ConfigParseException;
import seatmap.text.LineTokenizer.Token;

class LineTokenizerTest {

    private static List<Token> tokens(String line) {
        return LineTokenizer.tokenize(line, "test", 7);
    }

    @Test
    void splitsWordsAndQuotedText() {
        List<Token> tokens = tokens("  section ORCH \"Main  Floor\"  ");
        assertEquals(3, tokens.size());
        assertEquals(new Token("section", false), tokens.get(0));
        assertEquals(new Token("Main  Floor", true), tokens.get(2));
    }

    @Test
    void stripsCommentsButKeepsHashInsideQuotes() {
        assertEquals(List.of(new Token("row", false), new Token("A", false)), tokens("row A # front row"));
        assertEquals(List.of(new Token("venue", false), new Token("Club #9", true)), tokens("venue \"Club #9\"#x"));
        assertTrue(tokens("   # only a comment").isEmpty());
        assertTrue(tokens("").isEmpty());
    }

    @Test
    void handlesEscapes() {
        assertEquals("say \"hi\" \\ bye", tokens("\"say \\\"hi\\\" \\\\ bye\"").get(0).text());
    }

    @Test
    void reportsMalformedQuotesWithLineNumber() {
        ConfigParseException e = assertThrows(ConfigParseException.class, () -> tokens("venue \"open"));
        assertEquals(7, e.line());
        assertEquals("test", e.source());
        assertTrue(e.getMessage().startsWith("test:7: "));
        assertThrows(ConfigParseException.class, () -> tokens("venue \"bad \\n escape\""));
        assertThrows(ConfigParseException.class, () -> tokens("venue \"a\"b"));
        assertThrows(ConfigParseException.class, () -> tokens("ven\"ue"));
        assertThrows(ConfigParseException.class, () -> tokens("\"trailing\\"));
    }
}
