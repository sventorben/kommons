package de.sventorben.keycloak.kommons.orgs;

import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.email.freemarker.beans.ProfileBean;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.List;

/**
 * Makes the user pick exactly one organization for protocols that have no {@code scope} parameter, above all SAML.
 *
 * <p>Keycloak's own organization selection screen is driven by the requested organization scope and is therefore
 * unreachable for a SAML client. Required actions on the other hand are evaluated on every authentication regardless
 * of the login protocol, including a login that reuses an existing single sign-on session, which makes this the one
 * hook that cannot be bypassed.
 *
 * <p>The selected organization is written to the {@code kc.org} client note, the very note Keycloak's browser flow
 * uses. It reaches the authenticated client session through the regular note transfer, so a protocol mapper can read
 * it with {@code clientSession.getNote(OrganizationModel.ORGANIZATION_ATTRIBUTE)}.
 */
final class SelectOrganizationRequiredAction implements RequiredActionProvider {

    private static final Logger LOG = Logger.getLogger(SelectOrganizationRequiredAction.class);

    /** Shipped by Keycloak's base login theme and also used by the built-in organization selection. */
    private static final String FORM_TEMPLATE = "select-organization.ftl";

    /** Stateless, so a single instance is shared by the factory. */
    static final SelectOrganizationRequiredAction INSTANCE = new SelectOrganizationRequiredAction();

    private SelectOrganizationRequiredAction() {
    }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        if (!context.getRealm().isOrganizationsEnabled()) {
            return;
        }

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        boolean bindToSsoSession = OrganizationSessionBinding.isEnabled(context.getRealm());

        if (bindToSsoSession && adoptBoundOrganization(context, authSession)) {
            // The session is already bound, so there is nothing left to choose or to record.
            return;
        }

        promptWhenNeeded(context, authSession);

        if (bindToSsoSession) {
            bindSelection(context, authSession);
        }
    }

    /**
     * Applies the organization the single sign-on session is bound to. The injector authenticator normally did this
     * before Keycloak's organization handling ran; doing it again here also covers protocols and setups where the
     * injector is not in the flow.
     */
    private boolean adoptBoundOrganization(RequiredActionContext context, AuthenticationSessionModel authSession) {
        OrganizationModel bound = OrganizationSessionBinding.boundOrganization(
            context.getSession(), context.getRealm(), context.getUser());

        return OrganizationSessionBinding.applyTo(authSession, bound);
    }

    private void promptWhenNeeded(RequiredActionContext context, AuthenticationSessionModel authSession) {
        if (OIDCLoginProtocol.LOGIN_PROTOCOL.equals(authSession.getProtocol())) {
            // OpenID Connect is covered by Keycloak's own organization authenticator, which drives the selection from
            // the organization scope. Triggering here as well would ask the user twice.
            return;
        }

        if (authSession.getClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE) != null) {
            return;
        }

        List<OrganizationModel> organizations = memberships(context);

        if (organizations.size() == 1) {
            select(context, authSession, organizations.get(0));
        } else if (organizations.size() > 1) {
            authSession.addRequiredAction(SelectOrganizationRequiredActionFactory.PROVIDER_ID);
        }
    }

    /**
     * Records an organization that was already selected for this client session, so later client sessions in the
     * same single sign-on session adopt it. Covers the OpenID Connect case, where Keycloak's own authenticator did
     * the selecting.
     */
    private void bindSelection(RequiredActionContext context, AuthenticationSessionModel authSession) {
        OrganizationModel selected = OrganizationSessionBinding.resolve(context.getSession(),
            authSession.getClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE), context.getUser());

        if (selected != null) {
            OrganizationSessionBinding.bind(authSession, selected);
        }
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        context.challenge(createForm(context, null));
    }

    @Override
    public void processAction(RequiredActionContext context) {
        String alias = context.getHttpRequest().getDecodedFormParameters()
            .getFirst(OrganizationModel.ORGANIZATION_ATTRIBUTE);

        OrganizationModel organization = memberships(context).stream()
            .filter(candidate -> candidate.getAlias().equals(alias))
            .findFirst()
            .orElse(null);

        if (organization == null) {
            // Either nothing was submitted or the alias does not belong to an enabled organization of this user.
            LOG.debugf("Rejected organization selection '%s' for user %s", alias, context.getUser().getUsername());
            context.challenge(createForm(context, Messages.INVALID_REQUEST));
            return;
        }

        select(context, context.getAuthenticationSession(), organization);
        context.success();
    }

    private void select(RequiredActionContext context, AuthenticationSessionModel authSession,
                        OrganizationModel organization) {
        authSession.setClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE, organization.getId());
        LOG.debugf("Selected organization '%s' for this client session", organization.getAlias());

        if (OrganizationSessionBinding.isEnabled(context.getRealm())) {
            OrganizationSessionBinding.bind(authSession, organization);
        }
    }

    /**
     * The enabled organizations the authenticating user is a member of.
     */
    private List<OrganizationModel> memberships(RequiredActionContext context) {
        KeycloakSession session = context.getSession();
        UserModel user = context.getUser();

        if (user == null) {
            return List.of();
        }

        return session.getProvider(OrganizationProvider.class)
            .getByMember(user)
            .filter(OrganizationModel::isEnabled)
            .toList();
    }

    private Response createForm(RequiredActionContext context, String errorMessage) {
        LoginFormsProvider form = context.form()
            .setAttribute("user", new ProfileBean(context.getUser(), context.getSession()));

        if (errorMessage != null) {
            form.setError(errorMessage);
        }

        return form.createForm(FORM_TEMPLATE);
    }

    @Override
    public void close() {
    }
}
