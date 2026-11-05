# seatmap-booking

A venue seat-reservation engine in plain Java 21. It models a seat map, prices
seats, holds them for a limited time, turns holds into bookings, picks the best
available seats for a party, and reports occupancy and revenue. It can be used
as a library or through a small command-line program that keeps its state in
text files.

There are no runtime dependencies; JUnit 5 is used for the tests only.

## What it does

- **Seat maps** with sections, rows, seats, price categories, aisles and
  accessible seats, read from a plain-text layout file.
- **Events** with a price per category, automatic discounts (early-bird, group),
  promo codes with a validity window and an optional use limit, and a tiered
  refund policy, read from a plain-text event file.
- **Holds** that block seats until they expire. Time comes from an injected
  `java.time.Clock`, so expiry is deterministic in tests.
- **Bookings**: confirming a hold prices it and books the seats; cancelling a
  booking frees the seats and computes the refund.
- **Best-available allocation**: adjacent seats in one row that do not cross an
  aisle, avoiding stranded single seats, with optional category, section and
  accessible-seat requirements.
- **No double booking**: every read-then-write on an event runs under that
  event's lock.
- **Storage** behind an interface, with an in-memory implementation and a
  file-backed one, plus an append-only audit log.
- **Typed errors**: every failure is a subclass of `SeatmapException`
  (`ValidationException`, `NotFoundException`, `SeatUnavailableException`,
  `HoldExpiredException`, `PromoCodeException`, `BookingStateException`,
  `SalesClosedException`, `ConfigParseException`, `StorageException`).

## Build

Requires JDK 21 and Maven 3.9 or newer.

```
mvn package
```

This compiles the code, runs the tests and writes `target/seatmap-booking.jar`.

## Command line

```
java -jar target/seatmap-booking.jar [--data DIR] [--now INSTANT] COMMAND [ARGS]
```

`--data` names a directory that contains `events.conf` and the layout files it
refers to. The program writes `state.tsv` (holds and bookings) and `audit.log`
into the same directory. `--now` replaces the system clock with a fixed instant,
which makes scripted runs reproducible. Run `help` for the full command list.

Exit status: 0 success, 1 the command failed, 2 usage error, 3 internal error.

### Walk-through

The repository ships an example data directory. Copy it so that the state files
do not end up in `examples/` (`demo/` is ignored by git):

```
cp -r examples/orpheum demo
```

The example events take place in December 2026 and sales close when an event
starts, so the commands below pin the clock with `--now`.

```
$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:00:00Z events
HAMLET   Hamlet            2026-12-01T19:30:00Z  Orpheum Theatre  88 seats
MATINEE  Hamlet (matinee)  2026-12-05T14:00:00Z  Orpheum Theatre  88 seats
```

Price two seats without reserving them:

```
$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:00:00Z quote HAMLET --seats ORCH-A-6,ORCH-A-7 --promo SPRING10
Quote for 2 seat(s) at event HAMLET
  ORCH-A-6 (PREM)   85.00
  ORCH-A-7 (PREM)   85.00
  subtotal         170.00
  early-bird 15%   -25.50
  promo SPRING10   -14.45
  total            130.05
```

Hold named seats, or let the allocator choose:

```
$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:00:00Z hold HAMLET --customer "Ann Lee" --seats ORCH-A-6,ORCH-A-7
Held H-000001 for Ann Lee: ORCH-A-6 ORCH-A-7 (expires 2026-10-01T12:15:00Z)

$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:00:00Z hold HAMLET --customer Bob --best 4 --category PREM
Held H-000002 for Bob: ORCH-A-8 ORCH-A-9 ORCH-A-10 ORCH-A-11 (expires 2026-10-01T12:15:00Z)

$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:00:00Z hold HAMLET --customer Cy --best 2 --accessible 1 --ttl 30m
Held H-000003 for Cy: ORCH-D-2 ORCH-D-3 (expires 2026-10-01T12:30:00Z)
```

Confirm a hold, then look at the seat map:

```
$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:05:00Z confirm H-000001 --promo SPRING10
Confirmed B-000001 for Ann Lee: ORCH-A-6 ORCH-A-7
  ORCH-A-6 (PREM)   85.00
  ORCH-A-7 (PREM)   85.00
  subtotal         170.00
  early-bird 15%   -25.50
  promo SPRING10   -14.45
  total            130.05

$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:06:00Z availability HAMLET --map
Event HAMLET "Hamlet" at Orpheum Theatre, starts 2026-12-01T19:30:00Z
Section  Name       Capacity  Free  Held  Booked
ORCH     Orchestra        56    48     6       2
BALC     Balcony          32    32     0       0

ORCH  Orchestra
  A  ooo  ooxxhhhh  ooo
  B  ooo  oooooooo  ooo
  C  ooo  oooooooo  ooo
  D  ahh  oooooooo  oaa

BALC  Balcony
  A  oooooooooo
  B  oooooooooo
  C  oooooooooooo

o free  a free (accessible)  h held  x booked
```

A hold that is not confirmed in time is lost:

```
$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:20:00Z confirm H-000002
seatmap: hold H-000002 expired at 2026-10-01T12:15:00Z
```

Occupancy and revenue:

```
$ java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:20:00Z report HAMLET
Event HAMLET "Hamlet" at Orpheum Theatre, starts 2026-12-01T19:30:00Z

Occupancy by section
         Capacity  Booked  Held  Free  Booked %
  ORCH         56       2     2    52       3.6
  BALC         32       0     0    32       0.0
  TOTAL        88       2     2    84       2.3

Occupancy by category
         Capacity  Booked  Held  Free  Booked %
  PREM         24       2     0    22       8.3
  STD          32       0     2    30       0.0
  BALC         32       0     0    32       0.0
  TOTAL        88       2     2    84       2.3

Revenue
  1 confirmed and 0 cancelled booking(s), 2 seat(s) sold
  gross                    170.00
  discounts                 39.95
  sales                    130.05
  refunded                   0.00
  kept from cancellations    0.00
  net                      130.05
  by category (list price):
    PREM  2 seats  170.00
    STD   0 seats    0.00
    BALC  0 seats    0.00
  promo codes: SPRING10 x1
```

Cancel four days before the event (the 50 % refund tier) and read the audit
trail:

```
$ java -jar target/seatmap-booking.jar --data demo --now 2026-11-27T09:00:00Z cancel B-000001
Cancelled B-000001: refund 65.03 of 130.05

$ java -jar target/seatmap-booking.jar --data demo --now 2026-11-27T09:00:00Z audit
2026-10-01T12:00:00Z  HOLD_PLACED        HAMLET  H-000001  customer=Ann Lee seats=ORCH-A-6,ORCH-A-7 expires=2026-10-01T12:15:00Z
2026-10-01T12:00:00Z  HOLD_PLACED        HAMLET  H-000002  customer=Bob seats=ORCH-A-8,ORCH-A-9,ORCH-A-10,ORCH-A-11 expires=2026-10-01T12:15:00Z
2026-10-01T12:00:00Z  HOLD_PLACED        HAMLET  H-000003  customer=Cy seats=ORCH-D-2,ORCH-D-3 expires=2026-10-01T12:30:00Z
2026-10-01T12:05:00Z  BOOKING_CONFIRMED  HAMLET  B-000001  hold=H-000001 customer=Ann Lee seats=ORCH-A-6,ORCH-A-7 total=130.05 promo=SPRING10
2026-10-01T12:20:00Z  HOLD_EXPIRED       HAMLET  H-000002  customer=Bob seats=ORCH-A-8,ORCH-A-9,ORCH-A-10,ORCH-A-11
2026-11-27T09:00:00Z  BOOKING_CANCELLED  HAMLET  B-000001  customer=Ann Lee seats=ORCH-A-6,ORCH-A-7 refund=65.03
```

Other commands: `release HOLD` gives up a hold, `expire` removes every hold
whose time has run out, and `audit` takes `--event EVENT` and `--limit N`.

## File formats

Both formats are line-based. `#` starts a comment, indentation is free, and
names with spaces go in double quotes.

### Layout file

```
venue "Orpheum Theatre"

category PREM "Premium"
category STD  "Standard"

section ORCH "Orchestra"
  row A  1-3:STD | 4-11:PREM | 12-14:STD
  row D  1-2:STD+acc 3:STD | 4-11:STD | 12:STD 13-14:STD+acc
```

A row lists seat groups from left to right. A group is `FROM-TO:CATEGORY` or
`NUMBER:CATEGORY`; a range may run downwards (`14-1:STD`). `+acc` marks the
seats of a group as accessible. `|` is an aisle: seats on either side of it are
not adjacent. Section codes, row labels and category codes consist of letters,
digits and underscores. A seat is identified as `SECTION-ROW-NUMBER`, for
example `ORCH-D-13`.

### Event file

```
event HAMLET "Hamlet"
  venue orpheum.layout
  starts 2026-12-01T19:30:00Z
  hold-ttl 15m
  price PREM 85.00
  price STD  55.00
  rule early-bird 15% until 2026-11-01T00:00:00Z
  rule group 10% min 6
  promo SPRING10 10% from 2026-09-01T00:00:00Z until 2026-11-15T00:00:00Z max-uses 100
  promo FIVEOFF 5.00 from 2026-09-01T00:00:00Z until 2026-12-01T00:00:00Z
  refund 100% until 7d
  refund 50% until 24h
```

- `venue` is a layout file, relative to the event file. Every category of the
  venue needs exactly one `price`.
- Times are ISO-8601 instants. Durations are a whole number followed by `s`,
  `m`, `h` or `d`.
- `hold-ttl` is the default lifetime of a hold (10 minutes when left out).
- `refund 50% until 24h` means: cancelling at least 24 hours before the start
  refunds 50 % of what was paid. The tier with the longest lead time that still
  applies wins; with no matching tier nothing is refunded.

## Rules worth knowing

**Pricing.** The subtotal is the sum of the list prices. Of the automatic rules
that apply, only the one with the largest discount is used (the first one
declared wins a tie). An early-bird rule applies strictly before its `until`
instant; a group rule applies from its minimum number of seats in one booking.
A promo code is then applied to the reduced amount; the total never goes below
zero. Percentages round half up to the cent. A promo code is valid from its
`from` instant up to, but not including, `until`, and its use limit counts
confirmed bookings (a cancelled booking gives its use back). A booking is priced
when the hold is confirmed, not when the hold is placed.

**Holds.** A hold contains at most 12 seats, lasts for the event's `hold-ttl`
unless a different time is requested, and never outlives the start of the
event. It stops blocking seats the instant it expires. No new holds,
confirmations or cancellations are accepted once the event has started.

**Best-available allocation.** A party is seated in adjacent seats of one row,
never across an aisle. Among the blocks that fit, the allocator prefers, in
this order: the requested section (falling back to the others in layout order
unless the request is limited to that section), blocks that do not use up
accessible seats nobody asked for, blocks that do not leave a single free seat
stranded, rows nearer the front, blocks nearer the middle of the row, and
finally the leftmost block. With `--split` a party that does not fit in one
block is divided into several blocks within one section.

## Using it as a library

```java
EventCatalog catalog = EventConfigParser.parse(Path.of("examples/orpheum/events.conf"));
Clock clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
ReservationService service = new ReservationService(catalog, new InMemoryReservationRepository(), clock);

// Hold two named seats, then let the allocator pick four more next to each other.
Hold named = service.hold("MATINEE", List.of(SeatId.parse("ORCH-B-6"), SeatId.parse("ORCH-B-7")), "Ann");
Hold best = service.holdBestAvailable("MATINEE", SeatRequest.of(4).withCategory("BALC"), "Bob", null);

try {
    service.hold("MATINEE", List.of(SeatId.parse("ORCH-B-7")), "Cy");
} catch (SeatUnavailableException e) {
    System.out.println(e.getMessage() + " -> " + e.seats());
}

Booking booking = service.confirm(named.id(), null);
System.out.println(booking.id() + " " + booking.seats() + " total " + booking.total());
System.out.println(best.seats() + " held until " + best.expiresAt());
```

prints

```
seat ORCH-B-7 is not available for event MATINEE -> [ORCH-B-7]
B-000001 [ORCH-B-6, ORCH-B-7] total 120.00
[BALC-A-4, BALC-A-5, BALC-A-6, BALC-A-7] held until 2026-10-01T12:10:00Z
```

Pass a `FileReservationRepository` and a `FileAuditLog` instead to keep state on
disk, and `Clock.systemUTC()` for real time.

## Project layout

```
pom.xml
examples/orpheum/            example layout and event files
src/main/java/seatmap/
  venue/      seat map model and the layout parser
  pricing/    money, price lists, discount rules, promo codes, pricing engine
  event/      events, refund policy, event catalog and the event parser
  booking/    hold and booking value types
  alloc/      best-available seat allocator
  repo/       repository interface, in-memory and file-backed implementations
  audit/      audit log, in-memory and file-backed
  report/     occupancy and revenue reports
  service/    ReservationService, the entry point that ties the above together
  cli/        command-line front end
  text/       tokenizer, duration and tab-separated record helpers
  error/      exception types
src/test/java/seatmap/       unit tests per package, CLI and restart integration tests
```

## Tests

```
mvn test
```

The suite contains unit tests for the model, parsers, pricing, allocator,
storage, reports and service, concurrency tests that race threads for the same
seats, end-to-end tests that drive the command line
against a copy of `examples/orpheum`, and integration tests that rebuild the
service from its files between steps. Temporary files are created under
`target/`.

## Limitations

- The file-backed repository rewrites the whole state file on every change and
  is meant for one process at a time. Nothing stops two processes from writing
  the same data directory at once.
- State is kept per data directory in flat files; there is no database backend
  and no network interface.
- A booking can only be cancelled as a whole.
- A split party stays within one section, and splitting cannot be combined
  with a request for accessible seats.
- The layout format has no shorthand for odd/even seat numbering.
- All times are UTC instants; there is no time-zone handling.

## License

MIT, see [LICENSE](LICENSE).
