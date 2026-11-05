# Changelog

All notable changes to this project are recorded in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Nothing has
been released yet; the version in `pom.xml` is `0.1.0-SNAPSHOT`.

## Unreleased

### Added

- Venue model: sections, rows and seats with price categories, accessible
  seats and aisles, read from a plain-text layout file with errors reported as
  `file:line`.
- Events with a price list per category, a default hold time and a refund
  policy, read from an event file that may describe several events sharing
  one layout.
- Pricing: exact decimal money, early-bird and group rules (the best single
  rule applies), promo codes with a validity window and an optional use
  limit, and an itemised quote.
- Holds that expire on their own, measured against an injectable clock, and
  never outlive the start of the event.
- Bookings created by confirming a hold, cancellation with a refund that
  depends on how long before the event it happens.
- Best-available allocation: one block of adjacent seats that prefers the
  requested section, avoids wasting accessible seats, avoids leaving single
  seats behind, and favours front rows and the middle of a row; optional
  splitting of a party inside one section.
- `ReservationService`, which serialises changes per event so that concurrent
  callers cannot take the same seat twice.
- In-memory and file-backed repositories; the file-backed one keeps holds,
  bookings, promo-code use and id counters in one tab-separated state file
  that is replaced atomically.
- Audit log of holds, releases, expiries, confirmations and cancellations, in
  memory or appended to a file.
- Occupancy and revenue reports per section and per category.
- Command line: `events`, `availability` (with a seat map), `quote`, `hold`,
  `confirm`, `release`, `cancel`, `report`, `audit` and `expire`, with `--data`
  to choose the data directory and `--now` to pin the clock.
- Example data for a small theatre under `examples/orpheum`.

### Fixed

- A duration too large to represent, such as `999999999999999d`, is rejected
  with a validation error instead of an arithmetic exception.
- A hold requested with an enormous hold time is capped at the start of the
  event instead of failing with an overflow.
- The command line reports a missing data directory, a data directory without
  `events.conf` and an unusable `--data` path as usage errors, and prints a
  one-line message for an unexpected failure instead of a stack trace.
