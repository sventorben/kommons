package de.sventorben.keycloak.kommons.ldap;

import java.util.Locale;
import java.util.Optional;

/**
 * The LDAP authentication mechanisms that {@code com.sun.jndi.ldap.LdapPoolManager} keeps separate
 * connection pools for.
 *
 * <p>Each constant knows both how the JDK dump names its section ({@code "anonymous pools:"},
 * {@code "simple auth pools:"}, {@code "DIGEST-MD5 auth pools:"}) and the value used for the
 * {@code authentication} metric tag, so the two never drift apart.
 */
enum AuthMechanism {

    /** Unauthenticated connections; the dump calls these "anonymous". */
    NONE("none", "anonymous"),
    SIMPLE("simple", "simple"),
    DIGEST_MD5("digest-md5", "digest");

    /** The dump ends every section header with this, which is what makes a line a header at all. */
    private static final String SECTION_SUFFIX = "pools:";

    private final String label;
    private final String headerToken;

    AuthMechanism(String label, String headerToken) {
        this.label = label;
        this.headerToken = headerToken;
    }

    /** The value of the {@code authentication} tag on the exported metrics. */
    String label() {
        return label;
    }

    /**
     * Recognises a section header such as {@code "simple auth pools:"}. Returns empty when the line
     * does not open a section, which is what tells the parser that the dump format has changed.
     */
    static Optional<AuthMechanism> ofSectionHeader(String line) {
        if (!line.endsWith(SECTION_SUFFIX)) {
            return Optional.empty();
        }
        String lower = line.toLowerCase(Locale.ROOT);
        for (AuthMechanism mechanism : values()) {
            if (lower.contains(mechanism.headerToken)) {
                return Optional.of(mechanism);
            }
        }
        return Optional.empty();
    }
}
