package seatmap.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import seatmap.booking.SeatStatus;
import seatmap.event.Event;
import seatmap.venue.Seat;
import seatmap.venue.SeatCategory;
import seatmap.venue.SeatId;
import seatmap.venue.Section;

/** How full an event is, broken down by section and by seat category. */
public record OccupancyReport(String eventId, List<Group> sections, List<Group> categories, Group total) {

    /** Seat counts for one section, one category or the whole venue. */
    public record Group(String code, String name, int capacity, int booked, int held) {

        public int free() {
            return capacity - booked - held;
        }

        /** Share of seats that are booked, in percent with one decimal, e.g. {@code 37.5}. */
        public double bookedPercent() {
            return capacity == 0 ? 0.0 : Math.round(booked * 1000.0 / capacity) / 10.0;
        }
    }

    public OccupancyReport {
        sections = List.copyOf(sections);
        categories = List.copyOf(categories);
    }

    /**
     * @param statuses the status of every seat of the event's venue
     */
    public static OccupancyReport of(Event event, Map<SeatId, SeatStatus> statuses) {
        Map<String, int[]> bySection = new LinkedHashMap<>();
        Map<String, int[]> byCategory = new LinkedHashMap<>();
        for (Section section : event.venue().sections()) {
            bySection.put(section.code(), new int[3]);
        }
        for (String category : event.venue().categories().keySet()) {
            byCategory.put(category, new int[3]);
        }
        int[] all = new int[3];
        for (Seat seat : event.venue().seats()) {
            SeatStatus status = statuses.getOrDefault(seat.id(), SeatStatus.FREE);
            for (int[] counts : List.of(bySection.get(seat.id().section()), byCategory.get(seat.category()), all)) {
                counts[0]++;
                if (status == SeatStatus.BOOKED) {
                    counts[1]++;
                } else if (status == SeatStatus.HELD) {
                    counts[2]++;
                }
            }
        }
        List<Group> sections = new ArrayList<>();
        for (Section section : event.venue().sections()) {
            sections.add(group(section.code(), section.name(), bySection.get(section.code())));
        }
        List<Group> categories = new ArrayList<>();
        for (SeatCategory category : event.venue().categories().values()) {
            categories.add(group(category.code(), category.displayName(), byCategory.get(category.code())));
        }
        return new OccupancyReport(event.id(), sections, categories, group("TOTAL", "Total", all));
    }

    private static Group group(String code, String name, int[] counts) {
        return new Group(code, name, counts[0], counts[1], counts[2]);
    }
}
