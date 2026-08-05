package de.sventorben.keycloak.kommons.orgs;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionConfigModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Binds a whole single sign-on session to one organization, rather than letting every client session pick its own.
 *
 * <p>Keycloak stores the selected organization in the {@code kc.org} <em>client</em> note, so two clients in one
 * single sign-on session can end up on two different organizations. When this binding is enabled the first selection
 * is additionally recorded on the <em>user</em> session, and every later authentication in that session adopts it
 * instead of asking again.
 *
 * <p>The switch lives on the {@code kommons-orgs-select-organization} required action so that there is a single
 * place to configure it, even though the injector authenticator reads it too.
 */
final class OrganizationSessionBinding {

    private static final Logger LOG = Logger.getLogger(OrganizationSessionBinding.class);

    static final String CONFIG_BIND_TO_SSO_SESSION = "kommons.orgs.bind.to.sso.session";

    /**
     * Note on the user session holding the organization the session is bound to. Deliberately distinct from
     * {@link OrganizationModel#ORGANIZATION_ATTRIBUTE}, which Keycloak manages per client session.
     */
    static final String SESSION_NOTE = "kommons.orgs.bound.organization";

    private OrganizationSessionBinding() {
    }

    /**
     * Whether sessions should be bound to a single organization. Requires the required action to be both registered
     * and enabled, since that is where the switch is configured.
     */
    static boolean isEnabled(RealmModel realm) {
        RequiredActionProviderModel provider =
            realm.getRequiredActionProviderByAlias(SelectOrganizationRequiredActionFactory.PROVIDER_ID);

        if (provider == null || !provider.isEnabled()) {
            return false;
        }

        RequiredActionConfigModel config =
            realm.getRequiredActionConfigByAlias(SelectOrganizationRequiredActionFactory.PROVIDER_ID);

        return config != null
            && Boolean.parseBoolean(config.getConfigValue(CONFIG_BIND_TO_SSO_SESSION, Boolean.FALSE.toString()));
    }

    /**
     * The organization the current single sign-on session is already bound to, or {@code null}.
     *
     * <p>Reads the identity cookie rather than the authentication session, because the user session is not attached
     * yet while the browser flow is still running. On a fresh login there is no identity cookie, which correctly
     * yields {@code null}.
     *
     * @param user the authenticating user, or {@code null} to fall back to the user of the single sign-on session
     */
    static OrganizationModel boundOrganization(KeycloakSession session, RealmModel realm, UserModel user) {
        AuthenticationManager.AuthResult ssoSession =
            AuthenticationManager.authenticateIdentityCookie(session, realm, true);

        if (ssoSession == null) {
            return null;
        }

        UserSessionModel userSession = ssoSession.getSession();
        if (userSession == null) {
            return null;
        }

        return resolve(session, userSession.getNote(SESSION_NOTE), user == null ? ssoSession.getUser() : user);
    }

    /**
     * Resolves a recorded organization id, re-validating it: the organization must still exist, still be enabled,
     * and the user must still be a member. A stale binding therefore falls back to a fresh selection rather than
     * pinning the session to something the user may no longer have access to.
     */
    static OrganizationModel resolve(KeycloakSession session, String organizationId, UserModel user) {
        if (organizationId == null) {
            return null;
        }

        if (user == null) {
            return null;
        }

        OrganizationModel organization = session.getProvider(OrganizationProvider.class).getById(organizationId);

        if (!stillHolds(organization, user)) {
            LOG.debugf("Discarding stale organization binding '%s'", organizationId);
            return null;
        }

        return organization;
    }

    /**
     * A binding only holds while the organization still exists, is still enabled, and the user is still a member.
     */
    private static boolean stillHolds(OrganizationModel organization, UserModel user) {
        if (organization == null) {
            return false;
        }
        return organization.isEnabled() && organization.isMember(user);
    }

    /**
     * Records the organization on the user session. The note is transferred when the authentication session is
     * attached, which happens after the required actions have run.
     */
    static void bind(AuthenticationSessionModel authSession, OrganizationModel organization) {
        authSession.setUserSessionNote(SESSION_NOTE, organization.getId());
        LOG.debugf("Bound the SSO session to organization '%s'", organization.getAlias());
    }

    /**
     * Applies an existing binding to the current client session, so Keycloak's organization handling and the
     * protocol mappers see the organization without asking the user again.
     *
     * @return {@code true} when a binding was applied
     */
    static boolean applyTo(AuthenticationSessionModel authSession, OrganizationModel organization) {
        if (organization == null) {
            return false;
        }

        String current = authSession.getClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE);

        if (organization.getId().equals(current)) {
            return true;
        }

        if (current != null) {
            LOG.warnf("Overriding organization selection '%s' with the organization the SSO session is bound to "
                + "('%s'). The user was asked to pick although the session is already bound; add the "
                + "'Organization Scope Injector' authenticator ahead of the cookie authenticator to avoid this.",
                current, organization.getAlias());
        }

        authSession.setClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE, organization.getId());
        return true;
    }
}
