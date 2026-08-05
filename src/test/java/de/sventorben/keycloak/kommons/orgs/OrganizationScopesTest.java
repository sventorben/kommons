package de.sventorben.keycloak.kommons.orgs;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.organization.protocol.mappers.oidc.OrganizationMembershipMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the recognition of organization scopes inside a raw {@code scope} parameter.
 */
class OrganizationScopesTest {

    private final ClientModel client = clientWith("organization");

    @Test
    void recognizesTheBareScopeAsAny() {
        List<OrganizationScopes.Requested> requested = OrganizationScopes.parse(client, "openid organization profile");

        assertThat(requested).singleElement().satisfies(entry -> {
            assertThat(entry.rawValue()).isEqualTo("organization");
            assertThat(entry.scopeName()).isEqualTo("organization");
            assertThat(entry.kind()).isEqualTo(OrganizationScopes.Kind.ANY);
        });
    }

    @Test
    void recognizesAnAliasAsSpecific() {
        List<OrganizationScopes.Requested> requested = OrganizationScopes.parse(client, "openid organization:acme");

        assertThat(requested).singleElement().satisfies(entry -> {
            assertThat(entry.rawValue()).isEqualTo("organization:acme");
            assertThat(entry.kind()).isEqualTo(OrganizationScopes.Kind.SPECIFIC);
        });
    }

    @Test
    void recognizesTheWildcardAsAll() {
        List<OrganizationScopes.Requested> requested = OrganizationScopes.parse(client, "organization:*");

        assertThat(requested).singleElement()
            .extracting(OrganizationScopes.Requested::kind)
            .isEqualTo(OrganizationScopes.Kind.ALL);
    }

    @Test
    void keepsEveryOrganizationScopeSoTheyCanBeCounted() {
        List<OrganizationScopes.Requested> requested =
            OrganizationScopes.parse(client, "openid organization:acme organization:globex");

        assertThat(requested).hasSize(2)
            .extracting(OrganizationScopes.Requested::rawValue)
            .containsExactly("organization:acme", "organization:globex");
    }

    @Test
    void ignoresScopesWithoutTheOrganizationMembershipMapper() {
        assertThat(OrganizationScopes.parse(client, "openid profile email")).isEmpty();
        assertThat(OrganizationScopes.parse(client, "organizations organization_unit")).isEmpty();
    }

    @Test
    void honoursACustomOrganizationScopeName() {
        ClientModel renamed = clientWith("tenant");

        assertThat(OrganizationScopes.parse(renamed, "openid tenant:acme")).singleElement()
            .extracting(OrganizationScopes.Requested::kind)
            .isEqualTo(OrganizationScopes.Kind.SPECIFIC);
        assertThat(OrganizationScopes.parse(renamed, "openid organization")).isEmpty();
    }

    @Test
    void returnsNothingForAnUnusableInput() {
        assertThat(OrganizationScopes.parse(null, "organization")).isEmpty();
        assertThat(OrganizationScopes.parse(client, null)).isEmpty();
        assertThat(OrganizationScopes.parse(client, "   ")).isEmpty();
        assertThat(OrganizationScopes.parse(clientWithoutOrganizationScope(), "organization")).isEmpty();
    }

    private static ClientModel clientWith(String organizationScopeName) {
        ClientScopeModel organizationScope = mock(ClientScopeModel.class);
        when(organizationScope.getName()).thenReturn(organizationScopeName);
        when(organizationScope.getProtocolMappersStream())
            .thenAnswer(invocation -> Stream.of(protocolMapper(OrganizationMembershipMapper.PROVIDER_ID)));

        ClientModel client = mock(ClientModel.class);
        when(client.getClientScopes(true)).thenReturn(Map.of());
        when(client.getClientScopes(false)).thenReturn(Map.of(organizationScopeName, organizationScope));
        return client;
    }

    private static ClientModel clientWithoutOrganizationScope() {
        ClientScopeModel profileScope = mock(ClientScopeModel.class);
        when(profileScope.getProtocolMappersStream())
            .thenAnswer(invocation -> Stream.of(protocolMapper("oidc-full-name-mapper")));

        ClientModel client = mock(ClientModel.class);
        when(client.getClientScopes(true)).thenReturn(Map.of("profile", profileScope));
        when(client.getClientScopes(false)).thenReturn(Map.of());
        return client;
    }

    private static ProtocolMapperModel protocolMapper(String providerId) {
        ProtocolMapperModel mapper = new ProtocolMapperModel();
        mapper.setProtocolMapper(providerId);
        return mapper;
    }
}
