package de.sventorben.keycloak.kommons.orgs;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import de.sventorben.keycloak.kommons.KeycloakDockerContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientScopesResource;
import org.keycloak.admin.client.resource.OrganizationGroupsResource;
import org.keycloak.admin.client.resource.OrganizationsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Exercises the mapper against Keycloak's own organization groups, covering the
 * {@code label} x {@code prefix} x {@code flatten} matrix end to end.
 *
 * <p>The realm deliberately contains no {@code organizations} group tree, so the default {@code auto} source has to
 * resolve to organization groups.
 *
 * <p>The organization {@code acme} gets a top level group {@code sales} and a nested group
 * {@code engineering/leads}; the user is a direct member of both.
 */
@Testcontainers
class OrganizationGroupsMapperIT {

    private static final String REALM = "OrganizationGroupsMapperIT";
    private static final String MAPPER_NAME = "org-groups";

    private static final String CONFIG_PREFIX_GROUPS = "kommons.prefix.groups.with.organization";
    private static final String CONFIG_FLAT_GROUPS = "kommons.emit.flattened.group.claim";
    private static final String CONFIG_GROUP_SOURCE = "kommons.orgs.group.source";
    private static final String CONFIG_GROUP_LABEL = "kommons.orgs.group.label";
    private static final String CONFIG_PREFIX_SEPARATOR = "kommons.orgs.prefix.separator";

    @Container
    private static final KeycloakContainer KEYCLOAK_CONTAINER = KeycloakDockerContainer.create()
        .withRealmImportFile("OrganizationGroupsMapperIT-realm.json")
        .withFeaturesEnabled("organization");

    @BeforeAll
    static void createOrganizationWithGroups() {
        RealmResource realm = KEYCLOAK_CONTAINER.getKeycloakAdminClient().realm(REALM);
        UserRepresentation user = realm.users().list().stream()
            .filter(it -> "test".equals(it.getUsername()))
            .findFirst().orElseThrow(() -> new IllegalStateException("User 'test' not found"));

        OrganizationsResource organizations = realm.organizations();
        String organizationId = createOrganization(organizations, "acme");
        assumeThat(organizations.get(organizationId).members().addMember(user.getId()).getStatus()).isEqualTo(201);

        OrganizationGroupsResource groups = organizations.get(organizationId).groups();

        String salesId = createTopLevelGroup(groups, "sales");
        String engineeringId = createTopLevelGroup(groups, "engineering");
        String leadsId = createSubGroup(groups, engineeringId, "leads");

        // Direct membership in a top level and in a nested group.
        groups.group(salesId).addMember(user.getId());
        groups.group(leadsId).addMember(user.getId());
    }

    @Test
    @DisplayName("label=name, prefix=off, flatten=off")
    void namesNestedPerOrganization() throws JWSInputException {
        configure(new MapperConfig("name", false, false, null));

        assertThat(groupsOfOrganization("acme")).containsExactlyInAnyOrder("sales", "leads");
    }

    @Test
    @DisplayName("label=name, prefix=on, flatten=on")
    void prefixedNamesFlattened() throws JWSInputException {
        configure(new MapperConfig("name", true, true, null));

        assertThat(flatGroups()).containsExactlyInAnyOrder("acme_sales", "acme_leads");
    }

    @Test
    @DisplayName("label=path, prefix=off, flatten=on")
    void pathsFlattened() throws JWSInputException {
        configure(new MapperConfig("path", false, true, null));

        assertThat(flatGroups()).containsExactlyInAnyOrder("sales", "engineering/leads");
    }

    @Test
    @DisplayName("label=path, prefix=on, flatten=on, separator=::")
    void prefixedPathsWithCustomSeparator() throws JWSInputException {
        configure(new MapperConfig("path", true, true, "::"));

        assertThat(flatGroups()).containsExactlyInAnyOrder("acme::sales", "acme::engineering/leads");
    }

    @Test
    @DisplayName("label=path, prefix=off, flatten=off")
    void pathsNestedPerOrganization() throws JWSInputException {
        configure(new MapperConfig("path", false, false, null));

        assertThat(groupsOfOrganization("acme")).containsExactlyInAnyOrder("sales", "engineering/leads");
    }

    @SuppressWarnings("unchecked")
    private static List<String> flatGroups() throws JWSInputException {
        Object claim = accessToken().getOtherClaims().get("groups");
        assertThat(claim).isInstanceOf(List.class);
        return (List<String>) claim;
    }

    @SuppressWarnings("unchecked")
    private static List<String> groupsOfOrganization(String alias) throws JWSInputException {
        Object claim = accessToken().getOtherClaims().get("organization");
        assertThat(claim).isInstanceOf(Map.class);
        Map<String, Map<String, List<String>>> organizations = (Map<String, Map<String, List<String>>>) claim;
        assertThat(organizations).containsKey(alias);
        return organizations.get(alias).get("groups");
    }

    private static AccessToken accessToken() throws JWSInputException {
        Keycloak client = Keycloak.getInstance(
            KEYCLOAK_CONTAINER.getAuthServerUrl(), REALM, "test", "test", "test",
            null, null, null, false, null, "openid organization:acme");

        return new JWSInput(client.tokenManager().getAccessToken().getToken()).readJsonContent(AccessToken.class);
    }

    /** The mapper settings under test, so the combinations stay readable at the call sites. */
    private record MapperConfig(String label, boolean prefix, boolean flatten, String separator) {
    }

    private static void configure(MapperConfig config) {
        ClientScopesResource clientScopes = KEYCLOAK_CONTAINER.getKeycloakAdminClient().realm(REALM).clientScopes();

        ClientScopeRepresentation organizationScope = clientScopes.findAll().stream()
            .filter(it -> "organization".equals(it.getName()))
            .findFirst().orElseThrow(() -> new IllegalStateException("organization client scope not found"));

        ProtocolMapperRepresentation mapper = organizationScope.getProtocolMappers().stream()
            .filter(it -> MAPPER_NAME.equals(it.getName()))
            .findFirst().orElseThrow(() -> new IllegalStateException(MAPPER_NAME + " protocol mapper not found"));

        mapper.getConfig().put(CONFIG_GROUP_SOURCE, "auto");
        mapper.getConfig().put(CONFIG_GROUP_LABEL, config.label());
        mapper.getConfig().put(CONFIG_PREFIX_GROUPS, Boolean.toString(config.prefix()));
        mapper.getConfig().put(CONFIG_FLAT_GROUPS, Boolean.toString(config.flatten()));
        if (config.separator() != null) {
            mapper.getConfig().put(CONFIG_PREFIX_SEPARATOR, config.separator());
        } else {
            mapper.getConfig().remove(CONFIG_PREFIX_SEPARATOR);
        }

        clientScopes.get(organizationScope.getId()).getProtocolMappers().update(mapper.getId(), mapper);
    }

    private static String createOrganization(OrganizationsResource organizations, String name) {
        OrganizationRepresentation organization = new OrganizationRepresentation();
        organization.setName(name);
        organization.setEnabled(true);

        OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
        domain.setName(name);
        organization.addDomain(domain);

        return idOf(organizations.create(organization));
    }

    private static String createTopLevelGroup(OrganizationGroupsResource groups, String name) {
        return idOf(groups.addTopLevelGroup(group(name)));
    }

    private static String createSubGroup(OrganizationGroupsResource groups, String parentId, String name) {
        return idOf(groups.group(parentId).addSubGroup(group(name)));
    }

    private static GroupRepresentation group(String name) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(name);
        return group;
    }

    private static String idOf(Response response) {
        assumeThat(response.getStatus()).isIn(200, 201);
        String location = response.getHeaderString("Location");
        assumeThat(location).isNotNull();
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
