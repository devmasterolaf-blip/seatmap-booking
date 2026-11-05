package seatmap.cli;

import java.time.Clock;

/** Process entry point; all behaviour lives in {@link App}. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(new App(System.out, System.err, Clock.systemUTC()).run(args));
    }
}
