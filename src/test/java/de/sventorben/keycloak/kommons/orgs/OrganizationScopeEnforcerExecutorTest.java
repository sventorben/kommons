package de.sventorben.keycloak.kommons.orgs;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuthErrorException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.organization.protocol.mappers.oidc.OrganizationMembershipMapper;
import org.keycloak.services.clientpolicy.ClientPolicyEvent;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.AuthorizationRequestContext;
import org.keycloak.services.clientpolicy.context.TokenRefreshContext;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the rules the executor applies to a {@code scope} parameter. The wiring into the various endpoints
 * is exercised by {@link OrganizationScopeEnforcerIT} against a running Keycloak.
 */
class OrganizationScopeEnforcerExecutorTest {

    private final ClientModel client = organizationAwareClient();

    @Test
    void acceptsTheBareOrganizationScope() {
        assertThatCode(() -> authorizationRequest(defaults(), "openid organization"))
            .doesNotThrowAnyException();
    }

    @Test
    void acceptsASingleNamedOrganization() {
        assertThatCode(() -> authorizationRequest(defaults(), "openid organization:acme"))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsSeveralOrganizationScopes() {
        assertThatThrownBy(() -> authorizationRequest(defaults(), "openid organization:acme organization:globex"))
            .isInstanceOf(ClientPolicyException.class)
            .satisfies(rejectedWith("Only a single organization scope"));
    }

    @Test
    void rejectsAllOrganizations() {
        assertThatThrownBy(() -> authorizationRequest(defaults(), "openid organization:*"))
            .isInstanceOf(ClientPolicyException.class)
            .satisfies(rejectedWith("all organizations"));
    }

    @Test
    void rejectsAMissingOrganizationScope() {
        assertThatThrownBy(() -> authorizationRequest(defaults(), "openid profile"))
            .isInstanceOf(ClientPolicyException.class)
            .satisfies(rejectedWith("required"));
    }

    @Test
    void acceptsAMissingOrganizationScopeWhenNotRequired() {
        OrganizationScopeEnforcerExecutorConfiguration config = defaults().withRequireScope(false);

        assertThatCode(() -> authorizationRequest(config, "openid profile")).doesNotThrowAnyException();
    }

    @Test
    void rejectsANamedOrganizationWhenOnlyUserSelectionIsAllowed() {
        OrganizationScopeEnforcerExecutorConfiguration config = defaults()
            .withSelectionMode(OrganizationScopeEnforcerExecutorConfiguration.MODE_USER_SELECTED);

        assertThatThrownBy(() -> authorizationRequest(config, "openid organization:acme"))
            .isInstanceOf(ClientPolicyException.class)
            .satisfies(rejectedWith("selected by the user"));
        assertThatCode(() -> authorizationRequest(config, "openid organization")).doesNotThrowAnyException();
    }

    /**
     * The message of a {@link ClientPolicyException} is the OAuth error code, the human readable reason is carried
     * separately as the error detail.
     */
    private static Consumer<Throwable> rejectedWith(String expectedDetail) {
        return thrown -> {
            ClientPolicyException exception = (ClientPolicyException) thrown;
            assertThat(exception.getError()).isEqualTo(OAuthErrorException.INVALID_SCOPE);
            assertThat(exception.getErrorStatus()).isEqualTo(Response.Status.BAD_REQUEST);
            assertThat(exception.getErrorDetail()).contains(expectedDetail);
        };
    }

    @Test
    void treatsAnEmptyScopeOnRefreshAsInherited() {
        assertThatCode(() -> tokenRefresh(defaults(), null)).doesNotThrowAnyException();
        assertThatCode(() -> tokenRefresh(defaults(), "")).doesNotThrowAnyException();
    }

    @Test
    void stillNarrowsAnExplicitScopeOnRefresh() {
        assertThatThrownBy(() -> tokenRefresh(defaults(), "openid organization:*"))
            .isInstanceOf(ClientPolicyException.class);
    }

    @Test
    void ignoresEventsWithoutAScopeParameter() throws ClientPolicyException {
        OrganizationScopeEnforcerExecutor executor = executor(defaults());

        assertThatCode(() -> executor.executeOnEvent(() -> ClientPolicyEvent.TOKEN_REVOKE))
            .doesNotThrowAnyException();
    }

    private void authorizationRequest(OrganizationScopeEnforcerExecutorConfiguration config, String scope)
        throws ClientPolicyException {
        AuthorizationRequestContext context = mock(AuthorizationRequestContext.class);
        when(context.getEvent()).thenReturn(ClientPolicyEvent.AUTHORIZATION_REQUEST);
        when(context.getClient()).thenReturn(client);
        when(context.getScopeParameter()).thenReturn(scope);

        executor(config).executeOnEvent(context);
    }

    private void tokenRefresh(OrganizationScopeEnforcerExecutorConfiguration config, String scope)
        throws ClientPolicyException {
        TokenRefreshContext context = mock(TokenRefreshContext.class);
        when(context.getEvent()).thenReturn(ClientPolicyEvent.TOKEN_REFRESH);
        when(context.getClient()).thenReturn(client);
        when(context.getScopeParameter()).thenReturn(scope);

        executor(config).executeOnEvent(context);
    }

    private OrganizationScopeEnforcerExecutor executor(OrganizationScopeEnforcerExecutorConfiguration config) {
        KeycloakSession session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
        when(session.getContext()).thenReturn(mock(KeycloakContext.class));

        OrganizationScopeEnforcerExecutor executor = new OrganizationScopeEnforcerExecutor(session);
        executor.setupConfiguration(config);
        return executor;
    }

    private static OrganizationScopeEnforcerExecutorConfiguration defaults() {
        return new OrganizationScopeEnforcerExecutorConfiguration().parseWithDefaultValues();
    }

    private static ClientModel organizationAwareClient() {
        ProtocolMapperModel membershipMapper = new ProtocolMapperModel();
        membershipMapper.setProtocolMapper(OrganizationMembershipMapper.PROVIDER_ID);

        ClientScopeModel organizationScope = mock(ClientScopeModel.class);
        when(organizationScope.getName()).thenReturn("organization");
        when(organizationScope.getProtocolMappersStream()).thenAnswer(invocation -> Stream.of(membershipMapper));

        ClientModel client = mock(ClientModel.class);
        when(client.getClientScopes(true)).thenReturn(Map.of());
        when(client.getClientScopes(false)).thenReturn(Map.of("organization", organizationScope));
        return client;
    }
}
