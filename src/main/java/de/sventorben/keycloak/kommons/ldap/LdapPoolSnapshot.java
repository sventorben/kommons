package de.sventorben.keycloak.kommons.ldap;

import java.util.Map;

/**
 * Immutable point-in-time view of the JVM-global JNDI/LDAP connection pool
 * ({@code com.sun.jndi.ldap.LdapPoolManager}).
 *
 * @param accessible        {@code true} if the values were read from the JDK; {@code false} if the
 *                          JDK internals could not be accessed (in which case all numeric fields are 0)
 * @param idleTimeoutMillis idle timeout in milliseconds before a pooled connection is closed (0 = no timeout)
 * @param maxSize           configured maximum connections per identity pool (0 = unlimited)
 * @param prefSize          configured preferred connections per identity pool
 * @param initSize          configured initial connections per identity pool
 * @param byAuth            per-authentication-mechanism connection statistics, keyed by
 *                          {@code none}, {@code simple}, {@code digest-md5}
 */
record LdapPoolSnapshot(
    boolean accessible,
    long idleTimeoutMillis,
    int maxSize,
    int prefSize,
    int initSize,
    Map<String, AuthPoolStats> byAuth) {

    private static final Map<String, AuthPoolStats> EMPTY_BY_AUTH = Map.of(
        "none", AuthPoolStats.empty(),
        "simple", AuthPoolStats.empty(),
        "digest-md5", AuthPoolStats.empty());

    static LdapPoolSnapshot unavailable() {
        return new LdapPoolSnapshot(false, 0L, 0, 0, 0, EMPTY_BY_AUTH);
    }

    AuthPoolStats forAuth(String authentication) {
        return byAuth.getOrDefault(authentication, AuthPoolStats.empty());
    }
}
