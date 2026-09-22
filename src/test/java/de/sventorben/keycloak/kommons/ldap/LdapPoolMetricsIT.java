package de.sventorben.keycloak.kommons.ldap;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import de.sventorben.keycloak.kommons.KeycloakDockerContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the LDAP connection pool metrics.
 *
 * <p>A real Keycloak instance runs in Docker with our provider classes loaded from
 * {@code target/classes}, metrics enabled ({@code KC_METRICS_ENABLED=true}), the provider itself
 * switched on (it is opt-in) and the JDK-internal {@code java.naming} package exported for
 * reflection via {@code JAVA_OPTS_APPEND}. The test scrapes the Prometheus endpoint on the
 * management port and asserts that the {@code keycloak_ldap_pool_*} series are present and readable.
 *
 * <p>No LDAP user federation is configured, so the connection counts are zero; the point under test
 * is that the metrics are exported and that {@code keycloak_ldap_pool_accessible} reports {@code 1}
 * when the required {@code --add-exports} flag is present.
 */
@Testcontainers
class LdapPoolMetricsIT {

    private static final String ADD_EXPORTS =
        "--add-exports java.naming/com.sun.jndi.ldap=ALL-UNNAMED";

    /** The provider is opt-in, so without this nothing registers and no gauge is exported at all. */
    private static final String ENABLE_PROVIDER =
        "--spi-events-listener--kommons-ldap-pool-metrics--enabled=true";

    @Container
    private static final KeycloakContainer KEYCLOAK = KeycloakDockerContainer.create()
        .withEnabledMetrics()
        .withCustomCommand(ENABLE_PROVIDER)
        .withEnv("JAVA_OPTS_APPEND", ADD_EXPORTS);

    private static HttpClient http;
    private static String metricsUrl;

    @BeforeAll
    static void setUp() {
        http = HttpClient.newHttpClient();
        metricsUrl = KEYCLOAK.getMgmtServerUrl() + "/metrics";
    }

    @Test
    @DisplayName("keycloak_ldap_pool_accessible reports 1 when the JDK internals are opened")
    void accessibleIsOne() throws Exception {
        String metrics = scrapeMetrics();
        assertThat(metrics).containsPattern("keycloak_ldap_pool_accessible(\\{[^}]*})? 1\\.0");
    }

    @Test
    @DisplayName("pool configuration gauges are exported")
    void configurationGaugesExported() throws Exception {
        String metrics = scrapeMetrics();
        assertThat(metrics).contains("keycloak_ldap_pool_max_size");
        assertThat(metrics).contains("keycloak_ldap_pool_preferred_size");
        assertThat(metrics).contains("keycloak_ldap_pool_init_size");
        assertThat(metrics).contains("keycloak_ldap_pool_timeout_milliseconds");
    }

    @Test
    @DisplayName("connection gauges are split by authentication and state")
    void connectionGaugesSplitByAuthenticationAndState() throws Exception {
        String metrics = scrapeMetrics();
        // no LDAP configured, so every count is 0.0
        assertThat(metrics).containsPattern(
            "keycloak_ldap_pool_connections\\{[^}]*authentication=\"simple\"[^}]*state=\"idle\"[^}]*} 0\\.0");
        assertThat(metrics).containsPattern(
            "keycloak_ldap_pool_connections\\{[^}]*authentication=\"none\"[^}]*state=\"busy\"[^}]*} 0\\.0");
        assertThat(metrics).contains("keycloak_ldap_pool_identity_pools");
        assertThat(metrics).contains("authentication=\"digest-md5\"");
    }

    private String scrapeMetrics() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(metricsUrl))
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
