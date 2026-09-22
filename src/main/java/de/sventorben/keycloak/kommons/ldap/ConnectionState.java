package de.sventorben.keycloak.kommons.ldap;

import java.util.function.ToIntFunction;

/**
 * The states a pooled LDAP connection is counted in, as reported per connection identity in the
 * JDK dump and exported as the {@code state} tag of {@code keycloak_ldap_pool_connections}.
 *
 * <p>{@link #TOTAL} is the whole of a pool rather than a peer of the others: a connection is idle,
 * busy or expired, and all three are included in the total.
 */
enum ConnectionState {

    TOTAL("total", AuthPoolStats::total),
    IDLE("idle", AuthPoolStats::idle),
    BUSY("busy", AuthPoolStats::busy),
    EXPIRED("expired", AuthPoolStats::expired);

    private final String label;
    private final ToIntFunction<AuthPoolStats> count;

    ConnectionState(String label, ToIntFunction<AuthPoolStats> count) {
        this.label = label;
        this.count = count;
    }

    /** The value of the {@code state} tag on the exported metric. */
    String label() {
        return label;
    }

    /** The number of connections in this state within the given pool. */
    int countIn(AuthPoolStats stats) {
        return count.applyAsInt(stats);
    }
}
