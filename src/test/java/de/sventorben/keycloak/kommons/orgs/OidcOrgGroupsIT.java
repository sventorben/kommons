package de.sventorben.keycloak.kommons.orgs;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import de.sventorben.keycloak.kommons.KeycloakDockerContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientScopesResource;
import org.keycloak.admin.client.resource.OrganizationsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.*;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@Testcontainers
class OidcOrgGroupsIT {

    private static final Network NETWORK = Network.newNetwork();

    private static final String CONFIG_PREFIX_GROUPS = "kommons.prefix.groups.with.organization";
    private static final String CONFIG_FLAT_GROUPS = "kommons.emit.flattened.group.claim";

    @Container
    private static final KeycloakContainer KEYCLOAK_CONTAINER = KeycloakDockerContainer.create()
        .withRealmImportFile("OidcOrgGroupsIT-realm.json")
        .withFeaturesEnabled("organization");

    @BeforeAll
    public static void createOrganizationsAndMembership() {

        RealmResource realm = KEYCLOAK_CONTAINER.getKeycloakAdminClient()
            .realm("OidcOrgGroupsIT");
        UserRepresentation user = realm.users().list().stream()
            .filter(it -> "test".equals(it.getUsername()))
            .findFirst().orElseThrow(() -> new RuntimeException("User not found"));

        OrganizationsResource organizations = realm.organizations();
        createOrganization(organizations, "org-1", List.of(user));
        createOrganization(organizations, "org-2", List.of(user));
        createOrganization(organizations, "org-3", List.of());
    }

    private static void createOrganization(OrganizationsResource organizations, String name, List<UserRepresentation> members) {
        OrganizationRepresentation organization = createOrganization(name);
        Response response = organizations.create(organization);
        assumeThat(response.getStatus()).isEqualTo(201);
        String location = response.getHeaderString("Location");
        String orgId = location.substring(location.lastIndexOf("/") + 1);
        members.forEach(member ->
            assumeThat(organizations.get(orgId).members().addMember(member.getId()).getStatus()).isEqualTo(201)
        );
    }

    private static OrganizationRepresentation createOrganization(String name) {
        OrganizationRepresentation org = new OrganizationRepresentation();
        org.setName(name);
        org.setEnabled(true);

        OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
        domain.setName(name);
        org.addDomain(domain);

        return org;
    }

    @Test
    @DisplayName("Given Organization scope requested, when token is requested, then organization claim is present")
    void givenScopeRequestedThenClaimPresent() throws JWSInputException {
        AccessToken token = getAccessTokenWithScope("openid organization");
        assertThat(token.getOtherClaims()).containsKey("organization");
    }

    @Test
    @DisplayName("Given flat groups and prefix groups, when token is requested, then groups claim is present and groups are prefixed")
    void flatGroupsAndPrefixedGroups() throws JWSInputException {
        configure(true, true);

        AccessToken token = getAccessTokenWithScope("openid organization:*");
        assertThat(token.getOtherClaims()).containsKey("groups");
        assertThat(token.getOtherClaims().get("groups")).isInstanceOf(List.class);

        List<String> expectedGroups = List.of("org-1_org-1-1", "org-2_org-2-1", "org-2_org-2-2");
        assertThat((List<String>) token.getOtherClaims().get("groups")).containsExactlyInAnyOrderElementsOf(expectedGroups);
    }

    @Test
    @DisplayName("Given flat groups and non-prefixed groups, when token is requested, then groups claim is present and groups are not prefixed")
    void flatGroupsAndUnprefixedGroups() throws JWSInputException {
        configure(false, true);

        AccessToken token = getAccessTokenWithScope("openid organization:*");
        assertThat(token.getOtherClaims()).containsKey("groups");
        assertThat(token.getOtherClaims().get("groups")).isInstanceOf(List.class);

        List<String> expectedGroups = List.of("org-1-1", "org-2-1", "org-2-2");
        assertThat((List<String>) token.getOtherClaims().get("groups")).containsExactlyInAnyOrderElementsOf(expectedGroups);
    }

    @Test
    @DisplayName("Given groups per org and prefixed groups, when token is requested, then groups claim is present and groups are prefixed")
    void orgGroupsAndPrefixedGroups() throws JWSInputException {
        configure(true, false);

        AccessToken token = getAccessTokenWithScope("openid organization:*");

        assertThat(((Map<String, Map<String, List<String>>>) token.getOtherClaims().get("organization")).get("org-1").get("groups")).containsExactly("org-1_org-1-1");
        assertThat(((Map<String, Map<String, List<String>>>) token.getOtherClaims().get("organization")).get("org-2").get("groups")).containsExactly("org-2_org-2-1", "org-2_org-2-2");
    }

    @Test
    @DisplayName("Given groups per org and non-prefix groups, when token is requested, then groups claim is present and groups are not prefixed")
    void orgGroupsAndNonPrefixedGroups() throws JWSInputException {
        configure(false, false);

        AccessToken token = getAccessTokenWithScope("openid organization:*");

        assertThat(((Map<String, Map<String, List<String>>>) token.getOtherClaims().get("organization")).get("org-1").get("groups")).containsExactly("org-1-1");
        assertThat(((Map<String, Map<String, List<String>>>) token.getOtherClaims().get("organization")).get("org-2").get("groups")).containsExactly("org-2-1", "org-2-2");
    }

    @Test
    @DisplayName("Given single org in scope, when token is requested, then only groups of that org are present")
    void singleOrg() throws JWSInputException {
        configure(false, true);

        AccessToken token = getAccessTokenWithScope("openid organization:org-2");
        assertThat(token.getOtherClaims()).containsKey("groups");
        assertThat(token.getOtherClaims().get("groups")).isInstanceOf(List.class);

        List<String> expectedGroups = List.of("org-2-1", "org-2-2");
        assertThat((List<String>) token.getOtherClaims().get("groups")).containsExactlyInAnyOrderElementsOf(expectedGroups);
    }

    @Test
    @DisplayName("Given any org in scope, when token is requested, then no groups present")
    void anyOrg() throws JWSInputException {
        configure(false, true);

        AccessToken token = getAccessTokenWithScope("openid organization");
        assertThat(token.getOtherClaims()).doesNotContainKey("groups");
    }

    private static AccessToken getAccessTokenWithScope(String scope) throws JWSInputException {
        Keycloak ropc = Keycloak.getInstance(
            KEYCLOAK_CONTAINER.getAuthServerUrl(),
            "OidcOrgGroupsIT",
            "test",
            "test",
            "test",
            null,
            null,
            null,
            false,
            null,
            scope);
        String tokenString = ropc.tokenManager().getAccessToken().getToken();
        AccessToken token = new JWSInput(tokenString).readJsonContent(AccessToken.class);
        return token;
    }

    void configure(boolean prefixGroups, boolean flatGroups) {
        ClientScopesResource clientScopesResource = KEYCLOAK_CONTAINER.getKeycloakAdminClient()
            .realm("OidcOrgGroupsIT")
            .clientScopes();

        ClientScopeRepresentation scopeRepresentation = clientScopesResource.findAll().stream()
            .filter(it -> "organization".equals(it.getName()))
            .findFirst().orElseThrow(() -> new IllegalStateException("organization client scope not found"));
        ProtocolMapperRepresentation orgGroupsMapper = scopeRepresentation
            .getProtocolMappers().stream()
            .filter(it -> "org-groups".equals(it.getName()))
            .findFirst().orElseThrow(() -> new IllegalStateException("org-groups protocol mapper not found"));

        orgGroupsMapper.getConfig().put(CONFIG_PREFIX_GROUPS, Boolean.toString(prefixGroups));
        orgGroupsMapper.getConfig().put(CONFIG_FLAT_GROUPS, Boolean.toString(flatGroups));

        clientScopesResource.get(scopeRepresentation.getId())
            .getProtocolMappers()
            .update(orgGroupsMapper.getId(), orgGroupsMapper);
    }

}
