package de.sventorben.keycloak.kommons.ldap;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * A single trimmed line of the {@code LdapPoolManager#showStats} dump, together with the small
 * vocabulary needed to read it. Keeping that vocabulary here means the parser states below deal in
 * fields and counters rather than in substring arithmetic.
 *
 * <p>Two shapes of line carry numbers. A {@link Field} line labels one value:
 * <pre>
 * maximum pool size: 20
 * </pre>
 * A connection-identity line packs several {@link Counter}s behind a host/principal key:
 * <pre>
 * localhost:389:::null:cn=admin,dc=example,dc=org:size=2; use=2; busy=2; idle=0; expired=0
 * </pre>
 */
record PoolDumpLine(String text) {

    /** A labelled numeric field, written as {@code "<label> <number>"} at the start of a line. */
    enum Field {

        IDLE_TIMEOUT("idle timeout:"),
        MAX_SIZE("maximum pool size:"),
        PREFERRED_SIZE("preferred pool size:"),
        INITIAL_SIZE("initial pool size:"),
        /** Inside a section: how many connection identities that pool currently holds. */
        CURRENT_POOL_SIZE("current pool size:");

        private final String label;

        Field(String label) {
            this.label = label;
        }
    }

    /** A per-identity counter, written as {@code "<key><number>"} within a single line. */
    enum Counter {

        SIZE("size="),
        BUSY("busy="),
        IDLE("idle="),
        EXPIRED("expired=");

        private final String key;

        Counter(String key) {
            this.key = key;
        }
    }

    static PoolDumpLine of(String raw) {
        return new PoolDumpLine(raw.trim());
    }

    /** The mechanism whose section this line opens, or empty when it opens no section. */
    Optional<AuthMechanism> opensSectionFor() {
        return AuthMechanism.ofSectionHeader(text);
    }

    /** Whether this is a connection-identity line, i.e. one carrying {@link Counter}s. */
    boolean holdsIdentityCounters() {
        return text.contains(Counter.SIZE.key);
    }

    /**
     * The value of {@code field}, or empty when this line does not carry it. Empty is also the
     * answer for a label whose value will not parse, so a garbled dump keeps whatever the parser
     * had rather than resetting it.
     */
    OptionalLong valueOf(Field field) {
        if (!text.startsWith(field.label)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(text.substring(field.label.length()).trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** The value of {@code counter}, or 0 when it is absent or unparseable. */
    int countOf(Counter counter) {
        int start = text.indexOf(counter.key);
        if (start < 0) {
            return 0;
        }
        start += counter.key.length();
        int end = start;
        while (end < text.length() && isNumberChar(text.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(text.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ASCII digits and a leading minus; the dump never uses grouping separators or decimals. */
    private static boolean isNumberChar(char c) {
        return c == '-' || (c >= '0' && c <= '9');
    }
}
