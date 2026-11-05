package seatmap.alloc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import seatmap.venue.Row;
import seatmap.venue.Seat;
import seatmap.venue.Section;
import seatmap.venue.SeatId;
import seatmap.venue.Venue;

/**
 * Picks the best free seats for a party.
 *
 * <p>A party is seated in one block of adjacent seats inside a single row; adjacency never
 * crosses an aisle. Among all blocks that fit, the allocator prefers, in this order:
 * <ol>
 *   <li>the requested section, then the other sections in venue order;</li>
 *   <li>blocks that do not use accessible seats the party did not ask for;</li>
 *   <li>blocks that leave no orphan (a single free seat cut off from other free seats);</li>
 *   <li>rows closer to the front of the section;</li>
 *   <li>blocks closer to the middle of the row;</li>
 *   <li>the leftmost block.</li>
 * </ol>
 *
 * <p>When the request allows it and no single block exists, the party is split into
 * several blocks inside one section, largest blocks first, never leaving one person alone
 * if that can be avoided.
 */
public final class BestAvailableAllocator {

    /** A block of adjacent seats together with the values it is ranked by. */
    private record Candidate(List<Seat> seats, int sectionRank, int accessibleWaste, int orphans, int rowIndex,
            int centreDistance, int position) {
    }

    private static final Comparator<Candidate> RANKING = Comparator
            .comparingInt(Candidate::sectionRank)
            .thenComparingInt(Candidate::accessibleWaste)
            .thenComparingInt(Candidate::orphans)
            .thenComparingInt(Candidate::rowIndex)
            .thenComparingInt(Candidate::centreDistance)
            .thenComparingInt(Candidate::position);

    /**
     * @param occupied seats that are held or booked and therefore not free
     * @return the chosen seats in row order, or empty when the request cannot be met
     */
    public Optional<List<Seat>> allocate(Venue venue, Set<SeatId> occupied, SeatRequest request) {
        List<Section> sections = sectionOrder(venue, request);
        Candidate best = null;
        for (int rank = 0; rank < sections.size(); rank++) {
            Candidate candidate = bestBlock(sections.get(rank), rank, occupied, request, request.count());
            if (candidate != null && (best == null || RANKING.compare(candidate, best) < 0)) {
                best = candidate;
            }
        }
        if (best != null) {
            return Optional.of(best.seats());
        }
        if (!request.allowSplit()) {
            return Optional.empty();
        }
        for (int rank = 0; rank < sections.size(); rank++) {
            Optional<List<Seat>> split = split(sections.get(rank), rank, occupied, request);
            if (split.isPresent()) {
                return split;
            }
        }
        return Optional.empty();
    }

    private static List<Section> sectionOrder(Venue venue, SeatRequest request) {
        if (request.section() == null) {
            return venue.sections();
        }
        List<Section> order = new ArrayList<>();
        venue.section(request.section()).ifPresent(order::add);
        if (!request.sectionOnly()) {
            for (Section section : venue.sections()) {
                if (!section.code().equals(request.section())) {
                    order.add(section);
                }
            }
        }
        return order;
    }

    /** The best block of exactly {@code size} seats in the section, or {@code null}. */
    private static Candidate bestBlock(Section section, int sectionRank, Set<SeatId> occupied, SeatRequest request,
            int size) {
        Candidate best = null;
        List<Row> rows = section.rows();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            for (List<Seat> block : row.blocks()) {
                for (int start = 0; start + size <= block.size(); start++) {
                    Candidate candidate = candidate(row, block, start, size, sectionRank, rowIndex, occupied, request);
                    if (candidate != null && (best == null || RANKING.compare(candidate, best) < 0)) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static Candidate candidate(Row row, List<Seat> block, int start, int size, int sectionRank, int rowIndex,
            Set<SeatId> occupied, SeatRequest request) {
        int accessible = 0;
        for (int i = start; i < start + size; i++) {
            Seat seat = block.get(i);
            if (occupied.contains(seat.id())) {
                return null;
            }
            if (request.category() != null && !request.category().equals(seat.category())) {
                return null;
            }
            if (seat.accessible()) {
                accessible++;
            }
        }
        if (accessible < request.accessibleSeats()) {
            return null;
        }
        int orphans = 0;
        if (freeRun(block, start - 1, -1, occupied) == 1) {
            orphans++;
        }
        if (freeRun(block, start + size, 1, occupied) == 1) {
            orphans++;
        }
        Seat first = block.get(start);
        Seat last = block.get(start + size - 1);
        int centreDistance = Math.abs(first.position() + last.position() - (row.size() - 1));
        return new Candidate(List.copyOf(block.subList(start, start + size)), sectionRank,
                accessible - request.accessibleSeats(), orphans, rowIndex, centreDistance, first.position());
    }

    /** Number of consecutive free seats starting at {@code from} and walking in {@code step}. */
    private static int freeRun(List<Seat> block, int from, int step, Set<SeatId> occupied) {
        int length = 0;
        for (int i = from; i >= 0 && i < block.size() && !occupied.contains(block.get(i).id()); i += step) {
            length++;
        }
        return length;
    }

    private static Optional<List<Seat>> split(Section section, int sectionRank, Set<SeatId> occupied,
            SeatRequest request) {
        Set<SeatId> taken = new HashSet<>(occupied);
        List<Seat> chosen = new ArrayList<>();
        int remaining = request.count();
        while (remaining > 0) {
            Candidate block = null;
            for (int size = Math.min(remaining, longestRow(section)); size >= 1 && block == null; size--) {
                if (remaining - size == 1 && size > 2) {
                    continue;
                }
                block = bestBlock(section, sectionRank, taken, request, size);
            }
            if (block == null) {
                return Optional.empty();
            }
            for (Seat seat : block.seats()) {
                taken.add(seat.id());
                chosen.add(seat);
            }
            remaining -= block.seats().size();
        }
        chosen.sort(Comparator.comparing(Seat::id));
        return Optional.of(chosen);
    }

    private static int longestRow(Section section) {
        return section.rows().stream().mapToInt(Row::size).max().orElse(0);
    }
}
