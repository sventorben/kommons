package de.sventorben.keycloak.kommons.ldap;

import java.util.EnumMap;
import java.util.Map;

/**
 * Turns the {@code LdapPoolManager#showStats} dump into an {@link LdapPoolSnapshot}.
 *
 * <p>The dump is a flat sequence of lines with one piece of structure: a header such as
 * {@code "simple auth pools:"} opens a section, and everything after it belongs to that section
 * until the next header. The size fields appear twice, once for the manager and once inside every
 * section, so only the ones seen before the first header are the configured values.
 *
 * <p>When not a single section is recognised the dump is not what we expect, and the result is
 * {@link LdapPoolSnapshot#unavailable()} rather than a snapshot full of zeros: the zeros would be
 * invented rather than measured, and {@code accessible} is precisely the signal operators alert on.
 */
final class PoolDumpParser {

    private PoolDumpParser() {
    }

    static LdapPoolSnapshot parse(String dump) {
        DumpReader reader = new DumpReader();
        for (String line : dump.split("\\R")) {
            reader.accept(PoolDumpLine.of(line));
        }
        return reader.snapshot();
    }

    /**
     * Consumes the dump line by line. Lines before the first section carry the manager's configured
     * sizes; from the first section header on, every line belongs to the section currently open.
     */
    private static final class DumpReader {

        private final Map<AuthMechanism, AuthPoolStats> byAuth = new EnumMap<>(AuthMechanism.class);

        private long idleTimeout;
        private int maxSize;
        private int prefSize;
        private int initSize;

        private AuthMechanism mechanism;
        private PoolSection section;

        DumpReader() {
            for (AuthMechanism known : AuthMechanism.values()) {
                byAuth.put(known, AuthPoolStats.empty());
            }
        }

        void accept(PoolDumpLine line) {
            line.opensSectionFor().ifPresentOrElse(next -> {
                closeSection();
                mechanism = next;
                section = new PoolSection();
            }, () -> {
                if (section == null) {
                    readConfiguration(line);
                } else {
                    section.accept(line);
                }
            });
        }

        private void readConfiguration(PoolDumpLine line) {
            line.valueOf(PoolDumpLine.Field.IDLE_TIMEOUT).ifPresent(value -> idleTimeout = value);
            line.valueOf(PoolDumpLine.Field.MAX_SIZE).ifPresent(value -> maxSize = (int) value);
            line.valueOf(PoolDumpLine.Field.PREFERRED_SIZE).ifPresent(value -> prefSize = (int) value);
            line.valueOf(PoolDumpLine.Field.INITIAL_SIZE).ifPresent(value -> initSize = (int) value);
        }

        private void closeSection() {
            if (mechanism != null) {
                byAuth.put(mechanism, section.toStats());
            }
        }

        LdapPoolSnapshot snapshot() {
            closeSection();
            if (mechanism == null) {
                return LdapPoolSnapshot.unavailable();
            }
            return new LdapPoolSnapshot(true, idleTimeout, maxSize, prefSize, initSize, byAuth);
        }
    }

    /** The running totals of the one pool section currently being read. */
    private static final class PoolSection {

        private int identityPools;
        private int total;
        private int idle;
        private int busy;
        private int expired;

        void accept(PoolDumpLine line) {
            line.valueOf(PoolDumpLine.Field.CURRENT_POOL_SIZE)
                .ifPresent(value -> identityPools = (int) value);
            if (line.holdsIdentityCounters()) {
                total += line.countOf(PoolDumpLine.Counter.SIZE);
                busy += line.countOf(PoolDumpLine.Counter.BUSY);
                idle += line.countOf(PoolDumpLine.Counter.IDLE);
                expired += line.countOf(PoolDumpLine.Counter.EXPIRED);
            }
        }

        AuthPoolStats toStats() {
            return new AuthPoolStats(identityPools, total, idle, busy, expired);
        }
    }
}
