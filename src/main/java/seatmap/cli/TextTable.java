package seatmap.cli;

import java.util.ArrayList;
import java.util.List;

/** Renders rows of cells as aligned plain-text columns. */
final class TextTable {
    private final boolean[] rightAligned;
    private final List<String[]> rows = new ArrayList<>();

    /**
     * @param alignment one character per column: {@code 'l'} for left, {@code 'r'} for right
     */
    TextTable(String alignment) {
        rightAligned = new boolean[alignment.length()];
        for (int i = 0; i < alignment.length(); i++) {
            rightAligned[i] = alignment.charAt(i) == 'r';
        }
    }

    TextTable row(Object... cells) {
        if (cells.length != rightAligned.length) {
            throw new IllegalArgumentException("expected " + rightAligned.length + " cells, got " + cells.length);
        }
        String[] row = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            row[i] = String.valueOf(cells[i]);
        }
        rows.add(row);
        return this;
    }

    String render(String indent) {
        int[] widths = new int[rightAligned.length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder text = new StringBuilder();
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder(indent);
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    line.append("  ");
                }
                String padding = " ".repeat(widths[i] - row[i].length());
                line.append(rightAligned[i] ? padding + row[i] : row[i] + padding);
            }
            text.append(line.toString().stripTrailing()).append('\n');
        }
        return text.toString();
    }
}
