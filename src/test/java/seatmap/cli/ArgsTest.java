package seatmap.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ArgsTest {
    private static final Set<String> VALUES = Set.of("seats", "customer");
    private static final Set<String> FLAGS = Set.of("map");

    private static Args parse(String... tokens) {
        return Args.parse(List.of(tokens), VALUES, FLAGS);
    }

    @Test
    void separatesPositionalsOptionsAndFlags() {
        Args args = parse("hold", "--customer", "Ann", "HAMLET", "--map");

        assertEquals(List.of("hold", "HAMLET"), args.positionals());
        assertEquals(Optional.of("Ann"), args.value("customer"));
        assertEquals(Optional.empty(), args.value("seats"));
        assertTrue(args.flag("map"));
    }

    @Test
    void acceptsInlineValues() {
        Args args = parse("--seats=A-1-1,A-1-2", "--customer=");

        assertEquals(Optional.of("A-1-1,A-1-2"), args.value("seats"));
        assertEquals(Optional.of(""), args.value("customer"));
    }

    @Test
    void anOptionValueMayLookLikeAnOption() {
        assertEquals(Optional.of("--map"), parse("--customer", "--map").value("customer"));
        assertFalse(parse("--customer", "--map").flag("map"));
    }

    @Test
    void doubleDashEndsOptionParsing() {
        Args args = parse("release", "--", "--map");

        assertEquals(List.of("release", "--map"), args.positionals());
        assertFalse(args.flag("map"));
    }

    @Test
    void rejectsMalformedOptions() {
        assertEquals("unknown option --nope",
                assertThrows(UsageException.class, () -> parse("--nope")).getMessage());
        assertEquals("option --seats needs a value",
                assertThrows(UsageException.class, () -> parse("hold", "--seats")).getMessage());
        assertEquals("option --map does not take a value",
                assertThrows(UsageException.class, () -> parse("--map=yes")).getMessage());
        assertEquals("option --seats is given twice",
                assertThrows(UsageException.class, () -> parse("--seats", "a", "--seats", "b")).getMessage());
    }

    @Test
    void allowOnlyNamesTheOffendingOption() {
        Args args = parse("events", "--map", "--customer", "Ann");

        args.allowOnly("events", Set.of("map", "customer"));
        UsageException e = assertThrows(UsageException.class, () -> args.allowOnly("events", Set.of("customer")));
        assertEquals("option --map is not valid for 'events'", e.getMessage());
    }
}
