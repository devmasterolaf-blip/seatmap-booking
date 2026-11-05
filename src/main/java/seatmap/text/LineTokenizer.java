package seatmap.text;

import java.util.ArrayList;
import java.util.List;

import seatmap.error.ConfigParseException;

/**
 * Splits one line of a configuration file into tokens.
 *
 * <p>Tokens are separated by whitespace. A token in double quotes may contain spaces and
 * the escapes {@code \"} and {@code \\}. A {@code #} outside quotes starts a comment that
 * runs to the end of the line.
 */
public final class LineTokenizer {

    /** A token and whether it was written in quotes. */
    public record Token(String text, boolean quoted) {
        @Override
        public String toString() {
            return quoted ? "\"" + text + "\"" : text;
        }
    }

    private LineTokenizer() {
    }

    public static List<Token> tokenize(String line, String source, int lineNumber) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                break;
            } else if (c == '"') {
                StringBuilder text = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char q = line.charAt(i);
                    if (q == '\\') {
                        if (i + 1 >= n) {
                            throw new ConfigParseException(source, lineNumber, "dangling backslash in quoted text");
                        }
                        char escaped = line.charAt(i + 1);
                        if (escaped != '"' && escaped != '\\') {
                            throw new ConfigParseException(source, lineNumber,
                                    "unknown escape \\" + escaped + " in quoted text");
                        }
                        text.append(escaped);
                        i += 2;
                    } else if (q == '"') {
                        closed = true;
                        i++;
                        break;
                    } else {
                        text.append(q);
                        i++;
                    }
                }
                if (!closed) {
                    throw new ConfigParseException(source, lineNumber, "unterminated quoted text");
                }
                if (i < n && !Character.isWhitespace(line.charAt(i)) && line.charAt(i) != '#') {
                    throw new ConfigParseException(source, lineNumber,
                            "unexpected character after closing quote");
                }
                tokens.add(new Token(text.toString(), true));
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(line.charAt(i)) && line.charAt(i) != '#') {
                    if (line.charAt(i) == '"') {
                        throw new ConfigParseException(source, lineNumber,
                                "quote in the middle of a word");
                    }
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), false));
            }
        }
        return tokens;
    }
}
