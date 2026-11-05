package seatmap.venue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import seatmap.error.ValidationException;

/** An immutable seat map: categories plus sections of rows of seats. */
public final class Venue {
    private final String name;
    private final Map<String, SeatCategory> categories;
    private final List<Section> sections;
    private final Map<SeatId, Seat> seatIndex;

    public Venue(String name, Collection<SeatCategory> categories, List<Section> sections) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("venue name must not be blank");
        }
        if (sections == null || sections.isEmpty()) {
            throw new ValidationException("venue '" + name + "' has no sections");
        }
        this.name = name;
        Map<String, SeatCategory> byCode = new LinkedHashMap<>();
        for (SeatCategory category : categories) {
            if (byCode.put(category.code(), category) != null) {
                throw new ValidationException("duplicate category " + category.code());
            }
        }
        Map<String, Section> sectionCodes = new LinkedHashMap<>();
        Map<SeatId, Seat> index = new LinkedHashMap<>();
        for (Section section : sections) {
            if (sectionCodes.put(section.code(), section) != null) {
                throw new ValidationException("duplicate section " + section.code());
            }
            for (Row row : section.rows()) {
                for (Seat seat : row.seats()) {
                    if (!byCode.containsKey(seat.category())) {
                        throw new ValidationException("seat " + seat.id()
                                + " uses undeclared category " + seat.category());
                    }
                    index.put(seat.id(), seat);
                }
            }
        }
        this.categories = java.util.Collections.unmodifiableMap(byCode);
        this.sections = List.copyOf(sections);
        this.seatIndex = java.util.Collections.unmodifiableMap(index);
    }

    public String name() {
        return name;
    }

    /** Categories in declaration order, keyed by code. */
    public Map<String, SeatCategory> categories() {
        return categories;
    }

    public List<Section> sections() {
        return sections;
    }

    public Optional<Section> section(String code) {
        return sections.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    public Optional<Seat> seat(SeatId id) {
        return Optional.ofNullable(seatIndex.get(id));
    }

    public boolean hasSeat(SeatId id) {
        return seatIndex.containsKey(id);
    }

    /** Every seat in section, row and position order. */
    public List<Seat> seats() {
        return new ArrayList<>(seatIndex.values());
    }

    public int capacity() {
        return seatIndex.size();
    }
}
