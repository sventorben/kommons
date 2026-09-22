package de.sventorben.keycloak.kommons.ldap;

/**
 * Aggregated connection counts for a single JNDI/LDAP authentication pool
 * ({@code none}, {@code simple} or {@code digest-md5}), summed across all of that pool's
 * distinct connection-identity groups (host/port/principal combinations).
 *
 * @param identityPools number of distinct connection-identity groups currently held
 * @param total         total number of pooled connections
 * @param idle          connections that are idle and available for reuse
 * @param busy          connections that are currently in use
 * @param expired       connections that have expired but are not yet removed
 */
record AuthPoolStats(int identityPools, int total, int idle, int busy, int expired) {

    private static final AuthPoolStats EMPTY = new AuthPoolStats(0, 0, 0, 0, 0);

    static AuthPoolStats empty() {
        return EMPTY;
    }
}
