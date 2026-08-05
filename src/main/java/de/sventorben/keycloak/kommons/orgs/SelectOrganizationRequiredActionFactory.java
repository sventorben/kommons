package de.sventorben.keycloak.kommons.orgs;

import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.common.Profile;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

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
