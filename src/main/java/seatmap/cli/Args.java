package seatmap.cli;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A parsed command line: positional arguments plus {@code --name value} options and
 * {@code --name} flags. {@code --name=value} is accepted too, and a bare {@code --} ends
 * option parsing.
 */
public final class Args {
    private final List<String> positionals = new ArrayList<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Set<String> flags = new HashSet<>();

    private Args() {
    }

    /**
     * @param valueOptions names (without dashes) of options that take a value
     * @param flagOptions  names (without dashes) of options that do not
     */
    public static Args parse(List<String> tokens, Set<String> valueOptions, Set<String> flagOptions) {
        Args args = new Args();
        boolean optionsEnded = false;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (optionsEnded || !token.startsWith("--")) {
                args.positionals.add(token);
                continue;
            }
            if (token.equals("--")) {
                optionsEnded = true;
                continue;
            }
            String name = token.substring(2);
            String inline = null;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                inline = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            if (flagOptions.contains(name)) {
                if (inline != null) {
                    throw new UsageException("option --" + name + " does not take a value");
                }
                args.flags.add(name);
            } else if (valueOptions.contains(name)) {
                String value = inline;
                if (value == null) {
                    if (i + 1 >= tokens.size()) {
                        throw new UsageException("option --" + name + " needs a value");
                    }
                    value = tokens.get(++i);
                }
                if (args.values.put(name, value) != null) {
                    throw new UsageException("option --" + name + " is given twice");
                }
            } else {
                throw new UsageException("unknown option --" + name);
            }
        }
        return args;
    }

    public List<String> positionals() {
        return positionals;
    }

    public Optional<String> value(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public boolean flag(String name) {
        return flags.contains(name);
    }

    /** Fails if an option outside {@code allowed} was used; {@code command} names the culprit. */
    public void allowOnly(String command, Set<String> allowed) {
        List<String> used = new ArrayList<>(values.keySet());
        used.addAll(flags);
        for (String name : used) {
            if (!allowed.contains(name)) {
                throw new UsageException("option --" + name + " is not valid for '" + command + "'");
            }
        }
    }
}
