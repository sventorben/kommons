package de.sventorben.keycloak.kommons.ldap;

import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads live statistics from the JVM-global JNDI/LDAP connection pool managed by
 * {@code com.sun.jndi.ldap.LdapPoolManager}. Keycloak's LDAP user federation uses JNDI with
 * connection pooling enabled, but the JDK exposes no public API for these numbers.
 *
 * <p>It does however have a {@code public static void showStats(PrintStream)} that dumps the whole
 * pool state in one go, so a single reflective call is enough &mdash; no digging through
 * {@code Pool}, {@code ConnectionsRef} and {@code Connections} internals. Its output looks like:
 *
 * <pre>
 * ***** start *****
 * idle timeout: 300000
 * maximum pool size: 20
 * preferred pool size: 5
 * initial pool size: 1
 * protocol types: plain
 * authentication types: none simple
 * anonymous pools:
 * ===== Pool start ======================
 * maximum pool size: 20
 * ...
 * current pool size: 0
 * ====== Pool end =====================
 * simple auth pools:
 * ===== Pool start ======================
 * ...
 * current pool size: 1
 *    localhost:389:::null:cn=admin,dc=example,dc=org:size=2; use=2; busy=2; idle=0; expired=0
 * ====== Pool end =====================
 * ***** end *****
 * </pre>
 *
 * <p>{@code showStats} is public, but its package is not exported, so the call needs:
 * <pre>
 *   --add-exports java.naming/com.sun.jndi.ldap=ALL-UNNAMED
 * </pre>
 * The weaker {@code --add-exports} is deliberate: we only invoke a public member, so the deep
 * reflection that {@code --add-opens} grants is not required.
 *
 * <p>When the option is missing, or the JDK output changes shape so that not a single pool section
 * is recognised, {@link LdapPoolSnapshot#accessible()} is {@code false}. That matters: the metric
 * derived from it is the signal that the numbers can be trusted, so silently reporting zeros
 * against an unparseable dump would be worse than reporting nothing.
 */
final class LdapConnectionPoolIntrospector {

    private static final Logger LOG = Logger.getLogger(LdapConnectionPoolIntrospector.class);

    private static final String AUTH_NONE = "none";
    private static final String AUTH_SIMPLE = "simple";
    private static final String AUTH_DIGEST = "digest-md5";

    private final Method showStats;
    private final boolean available;

    private volatile boolean loggedReadFailure;

    LdapConnectionPoolIntrospector() {
        Method method = null;
        try {
            // initialize=false: don't run the class' static initializer while merely probing access.
            Class<?> poolManager = Class.forName("com.sun.jndi.ldap.LdapPoolManager", false,
                LdapConnectionPoolIntrospector.class.getClassLoader());
            method = poolManager.getMethod("showStats", PrintStream.class);
        } catch (Throwable t) {
            LOG.warnf("LDAP connection pool metrics are unavailable: cannot access JDK internals (%s). "
                + "Add the JVM option '--add-exports java.naming/com.sun.jndi.ldap=ALL-UNNAMED' to enable them. "
                + "The keycloak_ldap_pool_accessible metric will report 0.", t.toString());
        }

        this.showStats = method;
        this.available = method != null;
    }

    boolean isAvailable() {
        return available;
    }

    LdapPoolSnapshot snapshot() {
        if (!available) {
            return LdapPoolSnapshot.unavailable();
        }
        try {
            return parse(dumpStats());
        } catch (Throwable t) {
            if (!loggedReadFailure) {
                loggedReadFailure = true;
                LOG.warn("Failed to read LDAP connection pool statistics; reporting them as unavailable.", t);
            }
            return LdapPoolSnapshot.unavailable();
        }
    }

    /**
     * Invoking {@code showStats} forces LdapPoolManager's initializer, the same one that runs under
     * normal LDAP use; it reads the {@code com.sun.jndi.ldap.connect.pool.*} system properties.
     */
    private String dumpStats() throws ReflectiveOperationException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            showStats.invoke(null, out);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * Turns the dump into a snapshot. The size lines appear twice, once for the manager and once
     * inside every pool section; only the leading ones are the configured values, so parsing stops
     * looking for them as soon as the first section starts.
     */
    static LdapPoolSnapshot parse(String stats) {
        DumpReader reader = new DumpReader();
        for (String line : stats.split("\\R")) {
            reader.accept(line.trim());
        }
        return reader.snapshot();
    }

    /**
     * Consumes the dump line by line. Lines before the first section carry the manager's configured
     * sizes; from the first section header on, every line belongs to the section currently open.
     */
    private static final class DumpReader {

        private final Map<String, AuthPoolStats> byAuth = new LinkedHashMap<>();

        private long idleTimeout;
        private int maxSize;
        private int prefSize;
        private int initSize;

        private String auth;
        private PoolSection section;

        DumpReader() {
            byAuth.put(AUTH_NONE, AuthPoolStats.empty());
            byAuth.put(AUTH_SIMPLE, AuthPoolStats.empty());
            byAuth.put(AUTH_DIGEST, AuthPoolStats.empty());
        }

        void accept(String line) {
            String next = sectionOf(line);
            if (next != null) {
                closeSection();
                auth = next;
                section = new PoolSection();
            } else if (section == null) {
                readConfiguration(line);
            } else {
                section.accept(line);
            }
        }

        private void readConfiguration(String line) {
            idleTimeout = labelled(line, "idle timeout:", idleTimeout);
            maxSize = (int) labelled(line, "maximum pool size:", maxSize);
            prefSize = (int) labelled(line, "preferred pool size:", prefSize);
            initSize = (int) labelled(line, "initial pool size:", initSize);
        }

        private void closeSection() {
            if (auth != null) {
                byAuth.put(auth, section.toStats());
            }
        }

        LdapPoolSnapshot snapshot() {
            closeSection();
            if (auth == null) {
                // Not a single "<mechanism> pools:" section: the format is not what we expect, so the
                // zeros would be made up rather than measured.
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

        void accept(String line) {
            if (line.startsWith("current pool size:")) {
                identityPools = (int) labelled(line, "current pool size:", identityPools);
            } else if (line.contains("size=")) {
                total += extract(line, "size=");
                busy += extract(line, "busy=");
                idle += extract(line, "idle=");
                expired += extract(line, "expired=");
            }
        }

        AuthPoolStats toStats() {
            return new AuthPoolStats(identityPools, total, idle, busy, expired);
        }
    }

    /**
     * Maps a section header such as {@code "simple auth pools:"} to an authentication label, or
     * returns {@code null} when the line does not start a section.
     */
    private static String sectionOf(String line) {
        if (!line.endsWith("pools:")) {
            return null;
        }
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("anonymous")) {
            return AUTH_NONE;
        }
        if (lower.startsWith("simple")) {
            return AUTH_SIMPLE;
        }
        if (lower.contains("digest")) {
            return AUTH_DIGEST;
        }
        return null;
    }

    /** Reads the number behind {@code label}, or keeps {@code fallback} when the line does not match. */
    private static long labelled(String line, String label, long fallback) {
        if (!line.startsWith(label)) {
            return fallback;
        }
        try {
            return Long.parseLong(line.substring(label.length()).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Extracts the integer value that follows {@code key} in a per-identity line such as
     * {@code "host:389:::null:cn=admin:size=10; use=45; busy=2; idle=7; expired=1"}. Returns 0 when
     * the key is absent or the value cannot be parsed.
     */
    static int extract(String stats, String key) {
        int start = stats.indexOf(key);
        if (start < 0) {
            return 0;
        }
        start += key.length();
        int end = start;
        while (end < stats.length() && isNumberChar(stats.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(stats.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ASCII digits and a leading minus; the dump never uses grouping separators or decimals. */
    private static boolean isNumberChar(char c) {
        return c == '-' || (c >= '0' && c <= '9');
    }
}
