package de.sventorben.keycloak.kommons.orgs;

import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.common.Profile;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public final class SelectOrganizationRequiredActionFactory
    implements RequiredActionFactory, EnvironmentDependentProviderFactory {

    static final String PROVIDER_ID = "kommons-orgs-select-organization";

    @Override
    public String getDisplayText() {
        return "Select Organization";
    }

    /**
     * The organization is chosen per client session, so the action has to be evaluated on every authentication and
     * must never be marked as completed for good.
     */
    @Override
    public boolean isOneTimeAction() {
        return false;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        ProviderConfigProperty bindToSsoSession = new ProviderConfigProperty();
        bindToSsoSession.setName(OrganizationSessionBinding.CONFIG_BIND_TO_SSO_SESSION);
        bindToSsoSession.setLabel("Bind the organization to the SSO session");
        bindToSsoSession.setHelpText("If enabled, the first organization a user selects is remembered for the whole "
            + "single sign-on session and every later client adopts it instead of asking again. Switching then "
            + "requires a new session, meaning a logout. If disabled, the organization is chosen per client session, "
            + "so different clients in one session may end up on different organizations. "
            + "For OpenID Connect this also needs the 'Organization Scope Injector' authenticator in the browser "
            + "flow, ahead of the cookie authenticator, otherwise users are still asked and the answer is discarded.");
        bindToSsoSession.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        bindToSsoSession.setDefaultValue(Boolean.FALSE.toString());
        return List.of(bindToSsoSession);
    }

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return SelectOrganizationRequiredAction.INSTANCE;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return Profile.isFeatureEnabled(Profile.Feature.ORGANIZATION);
    }
}
