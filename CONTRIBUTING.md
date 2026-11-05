# Contributing

Thanks for taking the time to look at this project. Bug reports, questions and
patches are all welcome.

## Getting set up

You need JDK 21 and Maven 3.9 or newer. Nothing else has to be installed; the
only dependency is JUnit 5, which Maven downloads for the tests.

```
mvn test        # compile and run the tests
mvn package     # also build target/seatmap-booking.jar
```

To try the command line by hand, copy the example data first so that the state
files stay out of `examples/`:

```
cp -r examples/orpheum demo
java -jar target/seatmap-booking.jar --data demo --now 2026-10-01T12:00:00Z availability HAMLET --map
```

## Reporting a bug

Please include:

- the command or the code that shows the problem,
- the layout and event files involved (or the smallest ones that still show it),
- what you expected and what happened instead, including the full error message.

For a command-line problem, the contents of `state.tsv` and `audit.log` from the
data directory usually help.

## Making a change

- Keep the project dependency-free at runtime. Test-only dependencies need a
  good reason as well.
- Every change in behaviour comes with a test. A bug fix starts with a test that
  fails without the fix.
- Tests must not depend on the wall clock. `ReservationService` takes a
  `java.time.Clock`; the tests use `MutableClock` from `src/test`, and the
  command-line tests pass `--now`.
- Tests must not write outside the temporary directory JUnit gives them
  (`@TempDir`), which the build places under `target/`.
- User-facing failures are subclasses of `SeatmapException` with a message that
  names the offending value. Parsers report `file:line`. Do not let
  `IllegalArgumentException`, `NumberFormatException` and the like reach the
  caller for bad input.
- The command line exits with 1 for a command that failed and 2 for a malformed
  command line; keep new commands consistent with that, and add them to the
  usage text in `App` and to the README.
- Changes to `state.tsv` or `audit.log` must keep existing files readable, or
  bump the version in the state file header and say so in the changelog.

## Style

- Java 21, four spaces, lines up to 120 characters.
- Value types are records or final classes that validate their arguments in the
  constructor; there are no setters.
- Public types and non-obvious methods carry a short Javadoc comment that says
  what the code is for, not how it does it.
- Package names follow the layout described in the README; put new code in the
  package that owns the concept instead of adding a `util` package.

## Commits and pull requests

- One logical change per commit, with a message in the form
  `type(scope): summary` (`feat`, `fix`, `test`, `docs`, `refactor`, `build`,
  `chore`).
- Add a line to `CHANGELOG.md` under "Unreleased" for anything a user would
  notice.
- Make sure `mvn test` passes before opening a pull request, and describe in the
  pull request what the change does and how you tested it.

## License

By contributing you agree that your contribution is licensed under the MIT
license that covers the project.
