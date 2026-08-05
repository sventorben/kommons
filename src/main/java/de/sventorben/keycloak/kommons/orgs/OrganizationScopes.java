package de.sventorben.keycloak.kommons.orgs;

import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.organization.protocol.mappers.oidc.OrganizationMembershipMapper;
import org.keycloak.protocol.oidc.TokenManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Classifies the organization related entries of an OAuth {@code scope} parameter.
 *
 * <p>Keycloak's own {@link org.keycloak.organization.protocol.mappers.oidc.OrganizationScope} can only report the
 * <em>first</em> organization scope it finds, which is not enough to detect that a client asked for several of them.
 * This class applies the same recognition rule &mdash; a client scope is an organization scope when it carries the
 * {@code oidc-organization-membership-mapper} &mdash; but keeps every match, so callers can count them.
 *
 * <p>Resolution deliberately goes through the {@link ClientModel} rather than
 * {@code session.getContext().getClient()}, because the client is not guaranteed to be on the Keycloak context in
 * every client policy event.
 */
final class OrganizationScopes {

    /**
     * Value of a parameterized organization scope that maps to every organization the user is a member of,
     * i.e. {@code organization:*}.
     */
    private static final String ALL_ORGANIZATIONS = "*";

    private OrganizationScopes() {
    }

    /**
     * Returns the organization scopes contained in {@code rawScope}, in the order they were requested.
     *
     * @param client     the client the request was made for, may be {@code null}
     * @param rawScope   the raw {@code scope} parameter, may be {@code null} or blank
     * @return the recognized organization scopes, never {@code null}
     */
    static List<Requested> parse(ClientModel client, String rawScope) {
        if (isBlank(rawScope)) {
            return List.of();
        }

        Set<String> scopeNames = organizationScopeNames(client);
        if (scopeNames.isEmpty()) {
            return List.of();
        }

        return TokenManager.parseScopeParameter(rawScope)
            .map(entry -> classify(scopeNames, entry))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Names of the client scopes assigned to {@code client} (default and optional) that map organization membership.
     */
    static Set<String> organizationScopeNames(ClientModel client) {
        if (client == null) {
            return Set.of();
        }
        return Stream.concat(
                client.getClientScopes(true).values().stream(),
                client.getClientScopes(false).values().stream())
            .filter(OrganizationScopes::mapsOrganizations)
            .map(ClientScopeModel::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Requested classify(Set<String> scopeNames, String entry) {
        for (String scopeName : scopeNames) {
            if (entry.equals(scopeName)) {
                return new Requested(entry, scopeName, Kind.ANY);
            }

            String prefix = scopeName + ClientScopeModel.VALUE_SEPARATOR;
            if (entry.startsWith(prefix)) {
                String value = entry.substring(prefix.length());
                return new Requested(entry, scopeName, ALL_ORGANIZATIONS.equals(value) ? Kind.ALL : Kind.SPECIFIC);
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean mapsOrganizations(ClientScopeModel clientScope) {
        return clientScope.getProtocolMappersStream()
            .map(ProtocolMapperModel::getProtocolMapper)
            .anyMatch(OrganizationMembershipMapper.PROVIDER_ID::equals);
    }

    /**
     * How a requested organization scope maps to organizations. Mirrors
     * {@link org.keycloak.organization.protocol.mappers.oidc.OrganizationScope}.
     */
    enum Kind {

        /** {@code organization} &mdash; resolved from the user's single membership or from the user's selection. */
        ANY,

        /** {@code organization:<alias>} &mdash; a specific organization named by the client. */
        SPECIFIC,

        /** {@code organization:*} &mdash; every organization the user is a member of. */
        ALL
    }

    /**
     * A single organization scope found in a {@code scope} parameter.
     *
     * @param rawValue  the entry as requested, e.g. {@code organization:acme}
     * @param scopeName the name of the underlying client scope, e.g. {@code organization}
     * @param kind      how the entry maps to organizations
     */
    record Requested(String rawValue, String scopeName, Kind kind) {
    }
}
