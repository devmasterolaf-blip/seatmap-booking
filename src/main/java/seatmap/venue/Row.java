package seatmap.venue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import seatmap.error.ValidationException;

/** A row of seats in physical left-to-right order, possibly broken up by aisles. */
public final class Row {
    private final String section;
    private final String label;
    private final List<Seat> seats;

    public Row(String section, String label, List<Seat> seats) {
        this.section = Identifiers.requireCode(section, "section code");
        this.label = Identifiers.requireCode(label, "row label");
        if (seats == null || seats.isEmpty()) {
            throw new ValidationException("row " + section + "-" + label + " has no seats");
        }
        Set<Integer> numbers = new HashSet<>();
        int previousBlock = 0;
        for (int i = 0; i < seats.size(); i++) {
            Seat seat = seats.get(i);
            if (!seat.id().section().equals(section) || !seat.id().row().equals(label)) {
                throw new ValidationException("seat " + seat.id() + " does not belong to row "
                        + section + "-" + label);
            }
            if (!numbers.add(seat.id().number())) {
                throw new ValidationException("duplicate seat number " + seat.id().number()
                        + " in row " + section + "-" + label);
            }
            if (seat.position() != i) {
                throw new ValidationException("seat " + seat.id() + " has position "
                        + seat.position() + " but sits at index " + i);
            }
            if (seat.block() < previousBlock || seat.block() > previousBlock + 1) {
                throw new ValidationException("seat " + seat.id() + " has an out-of-order block index");
            }
            previousBlock = seat.block();
        }
        this.seats = List.copyOf(seats);
    }

    public String section() {
        return section;
    }

    public String label() {
        return label;
    }

    public List<Seat> seats() {
        return seats;
    }

    public int size() {
        return seats.size();
    }

    /** The seats grouped into aisle-delimited blocks, left to right. */
    public List<List<Seat>> blocks() {
        List<List<Seat>> blocks = new ArrayList<>();
        List<Seat> current = new ArrayList<>();
        int block = seats.get(0).block();
        for (Seat seat : seats) {
            if (seat.block() != block) {
                blocks.add(List.copyOf(current));
                current = new ArrayList<>();
                block = seat.block();
            }
            current.add(seat);
        }
        blocks.add(List.copyOf(current));
        return blocks;
    }
}
