package de.sventorben.keycloak.kommons.orgs;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.Optional;
import java.util.Set;

/**
 * Adds the organization scope to an authorization request that did not ask for it, so that a session is always
 * established in the context of an organization &mdash; without having to change any client.
 *
 * <p>Keycloak gates its organization handling on the requested {@code scope} parameter. The cookie authenticator
 * yields to the {@code Organization} sub-flow only when an organization scope was requested, and the organization
 * selection screen is only shown for the bare organization scope. Assigning the organization client scope as a
 * <em>default</em> scope does not help: the login side reads the raw {@code scope} request parameter, while only the
 * token mappers see the assigned client scopes. Rewriting the request parameter is therefore the one place that
 * influences both.
 *
 * <p>This authenticator never authenticates anybody. It rewrites the note and reports
 * {@link AuthenticationFlowContext#attempted()}, so it must be added as an {@code ALTERNATIVE} execution ahead of the
 * cookie authenticator.
 */
final class OrganizationScopeInjectorAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(OrganizationScopeInjectorAuthenticator.class);

    static final String CONFIG_SCOPE_NAME = "kommons.orgs.scope.name";

    /** Stateless, so a single instance is shared by the factory. */
    static final OrganizationScopeInjectorAuthenticator INSTANCE = new OrganizationScopeInjectorAuthenticator();

    private OrganizationScopeInjectorAuthenticator() {
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            inject(context);
        } catch (RuntimeException e) {
            // Never let a failure here block a login: without the scope the request simply behaves as before.
            LOG.warn("Failed to inject the organization scope, continuing without it", e);
        }
        context.attempted();
    }

    private void inject(AuthenticationFlowContext context) {
        if (!context.getRealm().isOrganizationsEnabled()) {
            return;
        }

        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        applySessionBinding(context, authSession);

        if (!OIDCLoginProtocol.LOGIN_PROTOCOL.equals(authSession.getProtocol())) {
            // Only OpenID Connect has a scope parameter. For SAML use the "Select Organization" required action.
            return;
        }

        ClientModel client = authSession.getClient();
        String requestedScope = authSession.getClientNote(OIDCLoginProtocol.SCOPE_PARAM);

        if (!OrganizationScopes.parse(client, requestedScope).isEmpty()) {
            LOG.debugf("Client %s already requested an organization scope, nothing to inject", client.getClientId());
            return;
        }

        String scopeName = resolveScopeName(context, client);
        if (scopeName == null) {
            return;
        }

        String updatedScope = requestedScope == null || requestedScope.isBlank()
            ? scopeName
            : requestedScope + " " + scopeName;
        authSession.setClientNote(OIDCLoginProtocol.SCOPE_PARAM, updatedScope);

        LOG.debugf("Injected organization scope '%s' for client %s", scopeName, client.getClientId());
    }

    /**
     * Adopts the organization the single sign-on session is already bound to, before Keycloak's organization
     * handling runs. This is what keeps a bound session from asking the user again for every new client, and it is
     * why this authenticator has to sit ahead of the cookie authenticator.
     *
     * <p>Runs for every protocol, not just OpenID Connect. It is inert unless the binding is switched on, since no
     * binding is ever recorded then.
     */
    private void applySessionBinding(AuthenticationFlowContext context, AuthenticationSessionModel authSession) {
        if (!OrganizationSessionBinding.isEnabled(context.getRealm())) {
            return;
        }

        if (authSession.getClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE) != null) {
            return;
        }

        OrganizationModel bound = OrganizationSessionBinding.boundOrganization(
            context.getSession(), context.getRealm(), context.getUser());

        if (OrganizationSessionBinding.applyTo(authSession, bound)) {
            LOG.debugf("Adopted organization '%s' from the bound SSO session", bound.getAlias());
        }
    }

    /**
     * Returns the organization client scope to request, either from the authenticator config or, when unset, from
     * the single organization client scope assigned to the client.
     */
    private String resolveScopeName(AuthenticationFlowContext context, ClientModel client) {
        String configured = configuredScopeName(context);
        if (configured != null) {
            return configured;
        }

        Set<String> assigned = OrganizationScopes.organizationScopeNames(client);
        if (assigned.size() == 1) {
            return assigned.iterator().next();
        }

        if (assigned.isEmpty()) {
            LOG.debugf("Client %s has no organization client scope assigned, nothing to inject",
                client.getClientId());
        } else {
            LOG.warnf("Client %s has %d organization client scopes assigned (%s). Configure '%s' on the "
                    + "authenticator to choose one, no scope was injected.",
                client.getClientId(), assigned.size(), assigned, CONFIG_SCOPE_NAME);
        }
        return null;
    }

    private String configuredScopeName(AuthenticationFlowContext context) {
        return Optional.ofNullable(context.getAuthenticatorConfig())
            .map(AuthenticatorConfigModel::getConfig)
            .map(config -> config.get(CONFIG_SCOPE_NAME))
            .filter(value -> !value.isBlank())
            .map(String::trim)
            .orElse(null);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        context.attempted();
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
