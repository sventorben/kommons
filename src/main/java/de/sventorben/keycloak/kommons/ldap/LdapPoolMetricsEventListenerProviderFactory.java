package de.sventorben.keycloak.kommons.ldap;

import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers {@link LdapPoolMetrics} with the global Micrometer registry at server startup, so the
 * {@code keycloak_ldap_pool_*} gauges appear on Keycloak's {@code /metrics} endpoint (management
 * port, enabled via {@code --metrics-enabled=true}).
 *
 * <p>Opt-in, because the underlying introspection reads JDK internals and needs extra
 * {@code --add-opens} flags to work:
 *
 * <pre>
 * --spi-events-listener-kommons-ldap-pool-metrics-enabled=true
 * </pre>
 *
 * <p>This factory is used purely as a startup hook: Keycloak instantiates and initialises every
 * enabled provider factory at boot, regardless of realm configuration. The
 * {@link EventListenerProvider} it creates is a no-op and does <em>not</em> need to be enabled as an
 * event listener in any realm.
 */
public final class LdapPoolMetricsEventListenerProviderFactory
    implements EventListenerProviderFactory, EnvironmentDependentProviderFactory {

    static final String PROVIDER_ID = "kommons-ldap-pool-metrics";

    private static final Logger LOG = Logger.getLogger(LdapPoolMetricsEventListenerProviderFactory.class);
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    // Strong reference so the gauge suppliers (and their introspector) are never garbage collected.
    private LdapPoolMetrics metrics;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return NoopEventListenerProvider.INSTANCE;
    }

    @Override
    public void init(Config.Scope config) {
        // Guarded because a session factory rebuild re-inits the factory, and re-binding would
        // register a second set of gauges on the (process-global) Micrometer registry.
        if (REGISTERED.compareAndSet(false, true)) {
            LdapConnectionPoolIntrospector introspector = new LdapConnectionPoolIntrospector();
            metrics = new LdapPoolMetrics(introspector);
            metrics.bindTo(Metrics.globalRegistry);
            LOG.infof("Registered LDAP connection pool metrics (keycloak_ldap_pool_*); "
                + "JDK introspection available: %s", introspector.isAvailable());
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return config.getBoolean("enabled", false);
    }

    @Override
    public boolean isGlobal() {
        // Deliberately false. "Global" here controls event fan-out, not lifecycle: returning true
        // would dispatch every realm's events to the no-op provider below for no benefit. The pool
        // being process-global is unrelated -- registration happens in init() either way.
        return false;
    }

    private static final class NoopEventListenerProvider implements EventListenerProvider {

        static final NoopEventListenerProvider INSTANCE = new NoopEventListenerProvider();

        @Override
        public void onEvent(Event event) {
        }

        @Override
        public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        }

        @Override
        public void close() {
        }
    }
}
