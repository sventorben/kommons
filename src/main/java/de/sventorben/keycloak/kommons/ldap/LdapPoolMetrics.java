package de.sventorben.keycloak.kommons.ldap;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.function.ToDoubleFunction;

/**
 * Registers {@code keycloak_ldap_pool_*} gauges backed by {@link LdapConnectionPoolIntrospector}.
 *
 * <p>Exposed meters:
 * <ul>
 *   <li>{@code keycloak_ldap_pool_accessible} — 1 if pool statistics can be read from the JDK, else 0</li>
 *   <li>{@code keycloak_ldap_pool_max_size} / {@code _preferred_size} / {@code _init_size} — configured pool sizing</li>
 *   <li>{@code keycloak_ldap_pool_timeout_milliseconds} — idle timeout (0 = no timeout)</li>
 *   <li>{@code keycloak_ldap_pool_identity_pools{authentication}} — number of connection-identity groups</li>
 *   <li>{@code keycloak_ldap_pool_connections{authentication,state}} — connection counts,
 *       {@code state} in {@code total|idle|busy|expired}</li>
 * </ul>
 *
 * <p>Each scrape reads a single {@link LdapPoolSnapshot}, memoized for a short interval so the many
 * gauges do not each trigger a separate reflective pass over the pool.
 *
 * <p>Every gauge is registered with a strong reference: Micrometer otherwise keeps only a
 * {@link java.lang.ref.WeakReference} to the supplier, and once it is collected the gauge reports
 * {@code NaN} forever. Holding on to this instance elsewhere does not help, the suppliers are
 * separate objects.
 */
final class LdapPoolMetrics implements MeterBinder {

    private static final String PREFIX = "keycloak.ldap.pool";
    private static final long CACHE_TTL_MILLIS = 2_000L;

    private final LdapConnectionPoolIntrospector introspector;

    private volatile LdapPoolSnapshot cached;
    private volatile long cachedAtMillis;

    LdapPoolMetrics(LdapConnectionPoolIntrospector introspector) {
        this.introspector = introspector;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PREFIX + ".accessible", () -> snapshot().accessible() ? 1 : 0)
            .description("1 if LDAP connection pool statistics can be read from the JDK, 0 otherwise")
            .strongReference(true)
            .register(registry);

        configGauge(registry, PREFIX + ".max.size",
            "Configured maximum number of connections per identity pool (0 = unlimited)",
            LdapPoolSnapshot::maxSize);
        configGauge(registry, PREFIX + ".preferred.size",
            "Configured preferred number of connections per identity pool",
            LdapPoolSnapshot::prefSize);
        configGauge(registry, PREFIX + ".init.size",
            "Configured initial number of connections per identity pool",
            LdapPoolSnapshot::initSize);
        configGauge(registry, PREFIX + ".timeout.milliseconds",
            "Idle timeout after which a pooled connection is closed (0 = no timeout)",
            LdapPoolSnapshot::idleTimeoutMillis);

        for (AuthMechanism auth : AuthMechanism.values()) {
            Gauge.builder(PREFIX + ".identity.pools",
                    () -> snapshot().forAuth(auth).identityPools())
                .description("Number of distinct connection-identity pools (host/port/principal combinations)")
                .tag("authentication", auth.label())
                .strongReference(true)
                .register(registry);

            for (ConnectionState state : ConnectionState.values()) {
                Gauge.builder(PREFIX + ".connections",
                        () -> state.countIn(snapshot().forAuth(auth)))
                    .description("Number of pooled LDAP connections by authentication mechanism and state")
                    .tag("authentication", auth.label())
                    .tag("state", state.label())
                    .strongReference(true)
                    .register(registry);
            }
        }
    }

    private void configGauge(MeterRegistry registry, String name, String description,
                             ToDoubleFunction<LdapPoolSnapshot> value) {
        Gauge.builder(name, () -> value.applyAsDouble(snapshot()))
            .description(description)
            .strongReference(true)
            .register(registry);
    }

    private LdapPoolSnapshot snapshot() {
        long now = System.currentTimeMillis();
        LdapPoolSnapshot current = cached;
        if (current != null && (now - cachedAtMillis) < CACHE_TTL_MILLIS) {
            return current;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (cached == null || (now - cachedAtMillis) >= CACHE_TTL_MILLIS) {
                cached = introspector.snapshot();
                cachedAtMillis = now;
            }
            return cached;
        }
    }
}
