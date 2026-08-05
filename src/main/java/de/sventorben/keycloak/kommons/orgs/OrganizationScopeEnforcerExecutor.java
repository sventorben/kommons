package de.sventorben.keycloak.kommons.orgs;

import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.OAuthErrorException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oidc.grants.device.clientpolicy.context.DeviceAuthorizationRequestContext;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyEvent;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.ClientModelContext;
import org.keycloak.services.clientpolicy.context.ScopeParameterContext;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Rejects requests whose {@code scope} parameter does not resolve to exactly one organization.
 *
 * @see OrganizationScopeEnforcerExecutorFactory
 */
class OrganizationScopeEnforcerExecutor implements ClientPolicyExecutorProvider<OrganizationScopeEnforcerExecutorConfiguration> {

    private static final Logger LOG = Logger.getLogger(OrganizationScopeEnforcerExecutor.class);

    /**
     * Events carrying a client supplied {@code scope} parameter that can widen the organizations in a token.
     *
     * <p>{@code SERVICE_ACCOUNT_TOKEN_REQUEST} is deliberately absent: the client credentials grant has no user and
     * therefore no organization membership to constrain.
     */
    private static final Set<ClientPolicyEvent> ENFORCED_EVENTS = EnumSet.of(
        ClientPolicyEvent.AUTHORIZATION_REQUEST,
        ClientPolicyEvent.PUSHED_AUTHORIZATION_REQUEST,
        ClientPolicyEvent.BACKCHANNEL_AUTHENTICATION_REQUEST,
        ClientPolicyEvent.DEVICE_AUTHORIZATION_REQUEST,
        ClientPolicyEvent.TOKEN_REFRESH,
        ClientPolicyEvent.TOKEN_EXCHANGE_REQUEST,
        ClientPolicyEvent.RESOURCE_OWNER_PASSWORD_CREDENTIALS_REQUEST);

    private final KeycloakSession session;
    private OrganizationScopeEnforcerExecutorConfiguration configuration;

    OrganizationScopeEnforcerExecutor(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void setupConfiguration(OrganizationScopeEnforcerExecutorConfiguration config) {
        configuration = Objects.requireNonNullElseGet(config,
            OrganizationScopeEnforcerExecutorConfiguration::new).parseWithDefaultValues();
    }

    @Override
    public Class<OrganizationScopeEnforcerExecutorConfiguration> getExecutorConfigurationClass() {
        return OrganizationScopeEnforcerExecutorConfiguration.class;
    }

    @Override
    public void executeOnEvent(ClientPolicyContext context) throws ClientPolicyException {
        ClientPolicyEvent event = context.getEvent();
        if (!ENFORCED_EVENTS.contains(event)) {
            return;
        }

        if (event == ClientPolicyEvent.DEVICE_AUTHORIZATION_REQUEST) {
            // The only enforced context that exposes neither the client nor the scope parameter directly.
            DeviceAuthorizationRequestContext deviceContext = (DeviceAuthorizationRequestContext) context;
            validate(session.getContext().getClient(), deviceContext.getRequest().getScope(), false);
            return;
        }

        ClientModel client = ((ClientModelContext) context).getClient();
        String rawScope = ((ScopeParameterContext) context).getScopeParameter();
        validate(client, rawScope, event == ClientPolicyEvent.TOKEN_REFRESH);
    }

    /**
     * @param absentScopeIsInherited whether an empty {@code scope} parameter means "keep what was granted before"
     *                               rather than "ask for nothing", which is the case when refreshing a token
     */
    private void validate(ClientModel client, String rawScope, boolean absentScopeIsInherited) throws ClientPolicyException {
        if (client == null) {
            // Without a client the assigned organization client scopes cannot be resolved. Leave the request to
            // Keycloak rather than rejecting something that was never classified.
            LOG.debug("Cannot resolve the client of the request, skipping organization scope enforcement");
            return;
        }

        List<OrganizationScopes.Requested> requested = OrganizationScopes.parse(client, rawScope);

        if (requested.isEmpty()) {
            validateNoneRequested(rawScope, absentScopeIsInherited);
        } else if (requested.size() > 1) {
            throw invalidScope("Only a single organization scope may be requested, but " + requested.size()
                + " were found: " + requested.stream().map(OrganizationScopes.Requested::rawValue).toList() + ".");
        } else {
            validateSingleRequested(requested.get(0));
        }
    }

    private void validateNoneRequested(String rawScope, boolean absentScopeIsInherited) throws ClientPolicyException {
        boolean inherited = absentScopeIsInherited && (rawScope == null || rawScope.isBlank());

        if (inherited || !configuration.isRequireScope()) {
            return;
        }

        throw invalidScope("An organization scope is required, but the request contains none.");
    }

    private void validateSingleRequested(OrganizationScopes.Requested single) throws ClientPolicyException {
        if (OrganizationScopes.Kind.ALL == single.kind()) {
            throw invalidScope("Requesting all organizations via '" + single.rawValue() + "' is not allowed.");
        }

        if (OrganizationScopes.Kind.SPECIFIC == single.kind() && configuration.isUserSelectedOnly()) {
            throw invalidScope("The organization must be selected by the user. Request '" + single.scopeName()
                + "' without a value instead of '" + single.rawValue() + "'.");
        }

        // Anything left resolves to a single organization: the user's only membership or the one they select.
    }

    private ClientPolicyException invalidScope(String detail) {
        return new ClientPolicyException(OAuthErrorException.INVALID_SCOPE, detail, Response.Status.BAD_REQUEST);
    }

    @Override
    public String getName() {
        return "Organization Scope Enforcer";
    }

    @Override
    public String getProviderId() {
        return OrganizationScopeEnforcerExecutorFactory.PROVIDER_ID;
    }
}
