package dev.raqueeb.cadence.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed command-line arguments.
 *
 * <p>Deliberately small: a subcommand, positional arguments, long options
 * with values, and boolean flags. No short options, no clustering, no
 * abbreviation.
 *
 * <p>The one behaviour worth having an opinion about is what happens to an
 * option nobody recognises. Ignoring it means {@code --jsno} silently does
 * nothing and the user concludes the tool is broken; so {@link #unknownIn}
 * lets each command declare what it accepts and report the rest. Guessing at
 * a correction would be worse than either — a tool that acts on what it
 * thinks you meant is a tool you cannot trust with a typo.
 */
public final class Args {

    private final String command;
    private final List<String> positional;
    private final Map<String, String> options;
    private final List<String> flags;

    private Args(String command, List<String> positional, Map<String, String> options,
            List<String> flags) {
        this.command = command;
        this.positional = positional;
        this.options = options;
        this.flags = flags;
    }

    /**
     * Parses a command line.
     *
     * <p>Accepts both {@code --name value} and {@code --name=value}. A
     * {@code --name} with nothing after it, or followed by another option, is
     * a flag.
     *
     * @param argv the arguments as given to main
     * @return the parsed arguments
     */
    public static Args parse(String[] argv) {
        String command = "";
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        List<String> flags = new ArrayList<>();

        int index = 0;
        if (argv.length > 0 && !argv[0].startsWith("-")) {
            command = argv[0];
            index = 1;
        }

        while (index < argv.length) {
            String argument = argv[index];

            if (!argument.startsWith("--")) {
                positional.add(argument);
                index++;
                continue;
            }

            String body = argument.substring(2);
            int equals = body.indexOf('=');

            if (equals >= 0) {
                options.put(body.substring(0, equals), body.substring(equals + 1));
                index++;
                continue;
            }

            // A value follows only if the next argument is not itself an
            // option. Without that check, `--json --count 3` would consume
            // `--count` as the value of `--json`.
            boolean hasValue = index + 1 < argv.length && !argv[index + 1].startsWith("--");
            if (hasValue) {
                options.put(body, argv[index + 1]);
                index += 2;
            } else {
                flags.add(body);
                index++;
            }
        }

        return new Args(command, List.copyOf(positional), Map.copyOf(options), List.copyOf(flags));
    }

    /**
     * The subcommand, or an empty string when none was given.
     *
     * @return the first non-option argument
     */
    public String command() {
        return command;
    }

    /**
     * The positional arguments, in order.
     *
     * @return the arguments that were not options
     */
    public List<String> positional() {
        return positional;
    }

    /**
     * A positional argument by index.
     *
     * @param index which one, from zero
     * @return the argument, or empty when there are fewer than that
     */
    public Optional<String> positional(int index) {
        return index >= 0 && index < positional.size()
                ? Optional.of(positional.get(index))
                : Optional.empty();
    }

    /**
     * An option's value.
     *
     * @param name the option name, without the leading dashes
     * @return the value, or empty when the option was not given
     */
    public Optional<String> option(String name) {
        return Optional.ofNullable(options.get(name));
    }

    /**
     * An option's value as an integer.
     *
     * @param name     the option name
     * @param fallback what to return when the option was not given
     * @return the value
     * @throws IllegalArgumentException when the value is not an integer
     */
    public int intOption(String name, int fallback) {
        Optional<String> raw = option(name);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.orElseThrow().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--" + name + " needs a whole number, got \"" + raw.orElseThrow() + "\"");
        }
    }

    /**
     * Whether a flag was given.
     *
     * <p>A flag given with a value counts as present, so {@code --json=true}
     * works as well as {@code --json}.
     *
     * @param name the flag name
     * @return true when the flag or an option of that name was given
     */
    public boolean flag(String name) {
        return flags.contains(name) || options.containsKey(name);
    }

    /**
     * The options and flags this command does not recognise.
     *
     * @param known the names the command accepts
     * @return the names that were given but are not in {@code known}, sorted
     */
    public List<String> unknownIn(String... known) {
        List<String> accepted = List.of(known);
        List<String> unknown = new ArrayList<>();

        for (String name : options.keySet()) {
            if (!accepted.contains(name)) {
                unknown.add(name);
            }
        }
        for (String name : flags) {
            if (!accepted.contains(name)) {
                unknown.add(name);
            }
        }

        unknown.sort(String::compareTo);
        return List.copyOf(unknown);
    }
}
