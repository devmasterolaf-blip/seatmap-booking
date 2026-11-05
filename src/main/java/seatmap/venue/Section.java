package seatmap.venue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import seatmap.error.ValidationException;

/** A named part of the venue holding rows ordered from the stage outwards. */
public final class Section {
    private final String code;
    private final String name;
    private final List<Row> rows;

    public Section(String code, String name, List<Row> rows) {
        this.code = Identifiers.requireCode(code, "section code");
        this.name = (name == null || name.isBlank()) ? code : name;
        if (rows == null || rows.isEmpty()) {
            throw new ValidationException("section " + code + " has no rows");
        }
        Set<String> labels = new HashSet<>();
        for (Row row : rows) {
            if (!row.section().equals(code)) {
                throw new ValidationException("row " + row.label() + " belongs to section "
                        + row.section() + ", not " + code);
            }
            if (!labels.add(row.label())) {
                throw new ValidationException("duplicate row " + row.label() + " in section " + code);
            }
        }
        this.rows = List.copyOf(rows);
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public List<Row> rows() {
        return rows;
    }

    public Optional<Row> row(String label) {
        return rows.stream().filter(r -> r.label().equals(label)).findFirst();
    }

    public int capacity() {
        return rows.stream().mapToInt(Row::size).sum();
    }
}
