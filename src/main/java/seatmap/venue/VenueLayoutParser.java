package seatmap.venue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import seatmap.error.ConfigParseException;
import seatmap.error.SeatmapException;
import seatmap.error.ValidationException;
import seatmap.text.LineTokenizer;
import seatmap.text.LineTokenizer.Token;

/**
 * Reads a venue from the plain-text layout format.
 *
 * <pre>
 * venue "Orpheum Theatre"
 * category PREM "Premium"
 * category STD  "Standard"
 *
 * section ORCH "Orchestra"
 *   row A  1-4:STD | 5-12:PREM | 13-16:STD
 *   row B  1-2:STD+acc 3-4:STD | 5-12:PREM | 13-16:STD
 * </pre>
 *
 * <p>A row lists seat groups from left to right. A group is {@code FROM-TO:CATEGORY} or
 * {@code NUMBER:CATEGORY}; a descending range numbers the seats right to left. The suffix
 * {@code +acc} marks the seats of a group as accessible. A {@code |} stands for an aisle.
 */
public final class VenueLayoutParser {
    private static final int MAX_SEATS_PER_GROUP = 500;

    private final String source;
    private String venueName;
    private final Map<String, SeatCategory> categories = new LinkedHashMap<>();
    private final List<Section> sections = new ArrayList<>();

    private String sectionCode;
    private String sectionName;
    private int sectionLine;
    private List<Row> sectionRows;

    private VenueLayoutParser(String source) {
        this.source = source;
    }

    public static Venue parse(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SeatmapException("cannot read layout file " + file + ": " + e.getMessage(), e);
        }
        return parse(text, file.getFileName().toString());
    }

    public static Venue parse(String text, String source) {
        VenueLayoutParser parser = new VenueLayoutParser(source);
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            parser.line(lines[i], i + 1);
        }
        return parser.finish(lines.length);
    }

    private void line(String raw, int lineNumber) {
        List<Token> tokens = LineTokenizer.tokenize(raw, source, lineNumber);
        if (tokens.isEmpty()) {
            return;
        }
        Token head = tokens.get(0);
        if (head.quoted()) {
            throw error(lineNumber, "expected a directive, found quoted text");
        }
        switch (head.text()) {
            case "venue" -> venue(tokens, lineNumber);
            case "category" -> category(tokens, lineNumber);
            case "section" -> section(tokens, lineNumber);
            case "row" -> row(tokens, lineNumber);
            default -> throw error(lineNumber, "unknown directive '" + head.text() + "'");
        }
    }

    private void venue(List<Token> tokens, int lineNumber) {
        if (tokens.size() != 2) {
            throw error(lineNumber, "expected: venue \"NAME\"");
        }
        if (venueName != null) {
            throw error(lineNumber, "venue name is declared twice");
        }
        if (tokens.get(1).text().isBlank()) {
            throw error(lineNumber, "venue name must not be blank");
        }
        venueName = tokens.get(1).text();
    }

    private void category(List<Token> tokens, int lineNumber) {
        if (tokens.size() < 2 || tokens.size() > 3) {
            throw error(lineNumber, "expected: category CODE [\"NAME\"]");
        }
        String code = code(tokens.get(1), "category code", lineNumber);
        if (categories.containsKey(code)) {
            throw error(lineNumber, "category " + code + " is declared twice");
        }
        String name = tokens.size() == 3 ? tokens.get(2).text() : code;
        categories.put(code, new SeatCategory(code, name));
    }

    private void section(List<Token> tokens, int lineNumber) {
        if (tokens.size() < 2 || tokens.size() > 3) {
            throw error(lineNumber, "expected: section CODE [\"NAME\"]");
        }
        closeSection();
        String code = code(tokens.get(1), "section code", lineNumber);
        for (Section existing : sections) {
            if (existing.code().equals(code)) {
                throw error(lineNumber, "section " + code + " is declared twice");
            }
        }
        sectionCode = code;
        sectionName = tokens.size() == 3 ? tokens.get(2).text() : code;
        sectionLine = lineNumber;
        sectionRows = new ArrayList<>();
    }

    private void row(List<Token> tokens, int lineNumber) {
        if (sectionCode == null) {
            throw error(lineNumber, "row appears before any section");
        }
        if (tokens.size() < 3) {
            throw error(lineNumber, "expected: row LABEL GROUP [GROUP|'|']...");
        }
        String label = code(tokens.get(1), "row label", lineNumber);
        for (Row existing : sectionRows) {
            if (existing.label().equals(label)) {
                throw error(lineNumber, "row " + label + " is declared twice in section " + sectionCode);
            }
        }
        List<Seat> seats = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        int block = 0;
        boolean lastWasAisle = true;
        for (int i = 2; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.quoted()) {
                throw error(lineNumber, "seat groups must not be quoted");
            }
            if (token.text().equals("|")) {
                if (lastWasAisle) {
                    throw error(lineNumber, "an aisle must have seats on both sides");
                }
                block++;
                lastWasAisle = true;
                continue;
            }
            group(token.text(), label, block, seats, numbers, lineNumber);
            lastWasAisle = false;
        }
        if (lastWasAisle) {
            throw error(lineNumber, "an aisle must have seats on both sides");
        }
        sectionRows.add(new Row(sectionCode, label, seats));
    }

    private void group(String spec, String rowLabel, int block, List<Seat> seats, Set<Integer> numbers,
            int lineNumber) {
        int colon = spec.indexOf(':');
        if (colon <= 0 || colon == spec.length() - 1) {
            throw error(lineNumber, "seat group '" + spec + "' must look like 1-8:CATEGORY");
        }
        String range = spec.substring(0, colon);
        String[] tail = spec.substring(colon + 1).split("\\+", -1);
        String category = tail[0];
        if (!categories.containsKey(category)) {
            throw error(lineNumber, "seat group '" + spec + "' uses undeclared category '" + category + "'");
        }
        boolean accessible = false;
        for (int i = 1; i < tail.length; i++) {
            if (tail[i].equals("acc")) {
                accessible = true;
            } else {
                throw error(lineNumber, "unknown seat flag '+" + tail[i] + "' in '" + spec + "'");
            }
        }
        int from;
        int to;
        int dash = range.indexOf('-');
        if (dash < 0) {
            from = number(range, spec, lineNumber);
            to = from;
        } else {
            from = number(range.substring(0, dash), spec, lineNumber);
            to = number(range.substring(dash + 1), spec, lineNumber);
        }
        int count = Math.abs(to - from) + 1;
        if (count > MAX_SEATS_PER_GROUP) {
            throw error(lineNumber, "seat group '" + spec + "' has more than " + MAX_SEATS_PER_GROUP + " seats");
        }
        int step = to >= from ? 1 : -1;
        for (int k = 0, number = from; k < count; k++, number += step) {
            if (!numbers.add(number)) {
                throw error(lineNumber, "seat number " + number + " appears twice in row " + rowLabel);
            }
            seats.add(new Seat(new SeatId(sectionCode, rowLabel, number), category, accessible, seats.size(), block));
        }
    }

    private int number(String text, String spec, int lineNumber) {
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw error(lineNumber, "seat group '" + spec + "' has an invalid seat number '" + text + "'");
        }
        if (value < 1) {
            throw error(lineNumber, "seat numbers start at 1, found " + value + " in '" + spec + "'");
        }
        return value;
    }

    private String code(Token token, String what, int lineNumber) {
        if (token.quoted() || !Identifiers.isCode(token.text())) {
            throw error(lineNumber, what + " '" + token.text()
                    + "' may only contain letters, digits and underscores");
        }
        return token.text();
    }

    private void closeSection() {
        if (sectionCode == null) {
            return;
        }
        if (sectionRows.isEmpty()) {
            throw error(sectionLine, "section " + sectionCode + " has no rows");
        }
        sections.add(new Section(sectionCode, sectionName, sectionRows));
        sectionCode = null;
    }

    private Venue finish(int lastLine) {
        closeSection();
        if (venueName == null) {
            throw error(lastLine, "missing 'venue \"NAME\"' line");
        }
        if (sections.isEmpty()) {
            throw error(lastLine, "the layout declares no sections");
        }
        try {
            return new Venue(venueName, categories.values(), sections);
        } catch (ValidationException e) {
            throw error(lastLine, e.getMessage());
        }
    }

    private ConfigParseException error(int lineNumber, String message) {
        return new ConfigParseException(source, lineNumber, message);
    }
}
