package seatmap.text;

import java.util.ArrayList;
import java.util.List;

import seatmap.error.ValidationException;

/**
 * Tab-separated records with escaping, used by the file formats of the audit log and the
 * file-backed repository. A backslash, tab, newline or carriage return inside a field is
 * written as {@code \\}, {@code \t}, {@code \n} or {@code \r}, so one record is always
 * exactly one line.
 */
public final class Fields {
    private Fields() {
    }

    public static String join(List<String> fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                line.append('\t');
            }
            String field = fields.get(i);
            for (int k = 0; k < field.length(); k++) {
                char c = field.charAt(k);
                switch (c) {
                    case '\\' -> line.append("\\\\");
                    case '\t' -> line.append("\\t");
                    case '\n' -> line.append("\\n");
                    case '\r' -> line.append("\\r");
                    default -> line.append(c);
                }
            }
        }
        return line.toString();
    }

    /** Reverses {@link #join(List)}. */
    public static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\\') {
                if (i + 1 >= line.length()) {
                    throw new ValidationException("record ends in the middle of an escape");
                }
                char escaped = line.charAt(++i);
                switch (escaped) {
                    case '\\' -> field.append('\\');
                    case 't' -> field.append('\t');
                    case 'n' -> field.append('\n');
                    case 'r' -> field.append('\r');
                    default -> throw new ValidationException("record has an unknown escape \\" + escaped);
                }
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
