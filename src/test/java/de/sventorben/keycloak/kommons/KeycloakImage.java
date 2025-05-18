package de.sventorben.keycloak.kommons;

final class KeycloakImage {

    private static final String LATEST_VERSION = "latest";
    private static final String NIGHTLY_VERSION = "nightly";
    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version", LATEST_VERSION);

    private final String version;
    private final String name;

    private KeycloakImage(String version) {
        this.version = version;
        this.name = "quay.io/keycloak/keycloak:" + KEYCLOAK_VERSION;
    }

    public static KeycloakImage fromConfig() {
        return new KeycloakImage(KEYCLOAK_VERSION);
    }

    public String getName() {
        return name;
    }

    public boolean isNightlyVersion() {
        return NIGHTLY_VERSION.equalsIgnoreCase(version);
    }

    public boolean isLatestVersion() {
        return LATEST_VERSION.equalsIgnoreCase(version);
    }

}
