package seatmap.event;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import seatmap.error.NotFoundException;
import seatmap.error.ValidationException;

/** The set of events on sale, keyed by event id. */
public final class EventCatalog {
    private final Map<String, Event> events;

    public EventCatalog(Collection<Event> events) {
        Map<String, Event> byId = new LinkedHashMap<>();
        for (Event event : events) {
            if (byId.put(event.id(), event) != null) {
                throw new ValidationException("event " + event.id() + " is declared twice");
            }
        }
        this.events = Collections.unmodifiableMap(byId);
    }

    /** Events in declaration order. */
    public Collection<Event> all() {
        return events.values();
    }

    public Optional<Event> find(String id) {
        return Optional.ofNullable(events.get(id));
    }

    public Event require(String id) {
        Event event = events.get(id);
        if (event == null) {
            throw new NotFoundException("event", id);
        }
        return event;
    }
}
