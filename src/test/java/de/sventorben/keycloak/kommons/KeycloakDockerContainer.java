package de.sventorben.keycloak.kommons;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.images.PullPolicy;

import java.time.Duration;

public final class KeycloakDockerContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakDockerContainer.class);

    private static final String KEYCLOAK_ADMIN_PASS = "admin";
    private static final String KEYCLOAK_ADMIN_USER = "admin";
    private static final int KEYCLOAK_HTTP_PORT = 8080;
    private static final int KEYCLOAK_METRICS_HTTP_PORT = 9000;

    public static KeycloakContainer create() {
        return create(Network.newNetwork());
    }

    public static KeycloakContainer create(Network network) {
        KeycloakImage keycloakImage = KeycloakImage.fromConfig();
        String fullImage = keycloakImage.getName();
        ImagePullPolicy pullPolicy = PullPolicy.defaultPolicy();
        if (keycloakImage.isLatestVersion() || keycloakImage.isNightlyVersion()) {
            pullPolicy = PullPolicy.alwaysPull();
        }
        KeycloakContainer container = new KeycloakContainer(fullImage);
        LOGGER.info("Running test with image: " + container.getDockerImageName());
        return container
            //.withDebugFixedPort(8787, false)
            .withImagePullPolicy(pullPolicy)
            .withProviderClassesFrom("target/classes")
            .withExposedPorts(KEYCLOAK_HTTP_PORT, KEYCLOAK_METRICS_HTTP_PORT)
            .withLogConsumer(new Slf4jLogConsumer(LOGGER).withSeparateOutputStreams())
            .withStartupTimeout(Duration.ofSeconds(90))
            .withNetwork(network)
            .withNetworkAliases("keycloak")
            .withAdminUsername(KEYCLOAK_ADMIN_USER)
            .withAdminPassword(KEYCLOAK_ADMIN_PASS);
    }

}
