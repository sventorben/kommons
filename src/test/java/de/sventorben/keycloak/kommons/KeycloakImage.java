package de.sventorben.keycloak.kommons;

final class KeycloakImage {

    private static final String REPOSITORY = "quay.io/keycloak/keycloak";
    private static final String LATEST_VERSION = "latest";
    private static final String NIGHTLY_VERSION = "nightly";
    private static final String KEYCLOAK_VERSION = System.getProperty("keycloak.version", LATEST_VERSION);

    /**
     * Digest pin as {@code <version>@sha256:<digest>}, supplied by the build. It is applied
     * only when it refers to the requested version, so overriding {@code keycloak.version}
     * (as the compatibility matrix does) falls back to plain tag resolution instead of
     * silently pulling the pinned image under a different version's name.
     */
    private static final String KEYCLOAK_IMAGE_PIN = System.getProperty("keycloak.image.pin", "");

    private final String version;
    private final String name;

    private KeycloakImage(String version) {
        this.version = version;
        this.name = REPOSITORY + ":" + tagFor(version);
    }

    public static KeycloakImage fromConfig() {
        return new KeycloakImage(KEYCLOAK_VERSION);
    }

    private static String tagFor(String version) {
        int separator = KEYCLOAK_IMAGE_PIN.indexOf('@');
        if (separator > 0 && KEYCLOAK_IMAGE_PIN.substring(0, separator).equals(version)) {
            return KEYCLOAK_IMAGE_PIN;
        }
        return version;
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
