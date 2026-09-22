package de.sventorben.keycloak.kommons.ldap;

import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

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
 * <p>Reading that text is {@link PoolDumpParser}'s job; this class only obtains it.
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
            return PoolDumpParser.parse(dumpStats());
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
}
