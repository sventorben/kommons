package de.sventorben.keycloak.kommons.orgs;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import de.sventorben.keycloak.kommons.KeycloakDockerContainer;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.OrganizationsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Exercises the client policy executor against a running Keycloak through the resource owner password credentials
 * grant, which is the one user bound grant that can be driven without a browser.
 *
 * <p>The realm applies the {@code kommons-orgs-scope-enforcer} to every client via an {@code any-client} policy.
 */
@Testcontainers
class OrganizationScopeEnforcerIT {

    private static final String REALM = "OrganizationScopeEnforcerIT";

    @Container
    private static final KeycloakContainer KEYCLOAK_CONTAINER = KeycloakDockerContainer.create()
        .withRealmImportFile("OrganizationScopeEnforcerIT-realm.json")
        .withFeaturesEnabled("organization");

    @BeforeAll
    static void createOrganizationsAndMembership() {
        RealmResource realm = KEYCLOAK_CONTAINER.getKeycloakAdminClient().realm(REALM);
        UserRepresentation user = realm.users().list().stream()
            .filter(it -> "test".equals(it.getUsername()))
            .findFirst().orElseThrow(() -> new IllegalStateException("User 'test' not found"));

        OrganizationsResource organizations = realm.organizations();
        createOrganization(organizations, "org-1", List.of(user));
        createOrganization(organizations, "org-2", List.of(user));
    }

    @Test
    @DisplayName("Given a single named organization, when a token is requested, then it is issued for that organization")
    void singleNamedOrganizationIsAccepted() throws JWSInputException {
        AccessToken token = accessTokenFor("openid organization:org-2");

        assertThat(token.getOtherClaims()).containsKey("organization");
    }

    @Test
    @DisplayName("Given the bare organization scope, when a token is requested, then the request is accepted")
    void bareOrganizationScopeIsAccepted() {
        // Without a browser the user cannot pick, so the token carries no organization claim. What matters here is
        // that the executor lets the request through.
        assertThatCode(() -> accessTokenFor("openid organization")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Given two organization scopes, when a token is requested, then the request is rejected")
    void severalOrganizationScopesAreRejected() {
        assertThatThrownBy(() -> accessTokenFor("openid organization:org-1 organization:org-2"))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(thrown -> assertThat(status(thrown)).isEqualTo(Response.Status.BAD_REQUEST));
    }

    @Test
    @DisplayName("Given all organizations are requested, when a token is requested, then the request is rejected")
    void allOrganizationsAreRejected() {
        assertThatThrownBy(() -> accessTokenFor("openid organization:*"))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(thrown -> assertThat(status(thrown)).isEqualTo(Response.Status.BAD_REQUEST));
    }

    @Test
    @DisplayName("Given no organization scope, when a token is requested, then the request is rejected")
    void missingOrganizationScopeIsRejected() {
        assertThatThrownBy(() -> accessTokenFor("openid profile"))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(thrown -> assertThat(status(thrown)).isEqualTo(Response.Status.BAD_REQUEST));
    }

    private static Response.Status status(Throwable thrown) {
        return Response.Status.fromStatusCode(((WebApplicationException) thrown).getResponse().getStatus());
    }

    private static AccessToken accessTokenFor(String scope) throws JWSInputException {
        Keycloak client = Keycloak.getInstance(
            KEYCLOAK_CONTAINER.getAuthServerUrl(), REALM, "test", "test", "test",
            null, null, null, false, null, scope);

        String token = client.tokenManager().getAccessToken().getToken();
        return new JWSInput(token).readJsonContent(AccessToken.class);
    }

    private static void createOrganization(OrganizationsResource organizations, String name,
                                           List<UserRepresentation> members) {
        OrganizationRepresentation organization = new OrganizationRepresentation();
        organization.setName(name);
        organization.setEnabled(true);

        OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
        domain.setName(name);
        organization.addDomain(domain);

        Response response = organizations.create(organization);
        assumeThat(response.getStatus()).isEqualTo(201);

        String location = response.getHeaderString("Location");
        String organizationId = location.substring(location.lastIndexOf('/') + 1);
        members.forEach(member ->
            assumeThat(organizations.get(organizationId).members().addMember(member.getId()).getStatus())
                .isEqualTo(201));
    }
}
