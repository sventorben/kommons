package de.sventorben.keycloak.kommons.orgs;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.common.Profile;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

import static de.sventorben.keycloak.kommons.orgs.OrganizationScopeInjectorAuthenticator.CONFIG_SCOPE_NAME;

public final class OrganizationScopeInjectorAuthenticatorFactory
    implements AuthenticatorFactory, EnvironmentDependentProviderFactory {

    static final String PROVIDER_ID = "kommons-orgs-scope-injector";

    /**
     * The authenticator only ever reports {@code attempted}, so it must not be {@code REQUIRED}: a required execution
     * disables every alternative of its parent flow and would break cookie based single sign-on.
     */
    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
        AuthenticationExecutionModel.Requirement.ALTERNATIVE,
        AuthenticationExecutionModel.Requirement.DISABLED
    };

    @Override
    public String getDisplayType() {
        return "Organization Scope Injector";
    }

    @Override
    public String getReferenceCategory() {
        return "organization";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Adds the organization scope to OpenID Connect authorization requests that did not ask for it, so "
            + "that the organization selection is triggered and the organization ends up in the token even for "
            + "clients that cannot be changed. Add this as the first ALTERNATIVE execution of the browser flow, "
            + "before the cookie authenticator.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty scopeName = new ProviderConfigProperty();
        scopeName.setName(CONFIG_SCOPE_NAME);
        scopeName.setLabel("Organization scope name");
        scopeName.setHelpText("Name of the client scope to request, for example 'organization'. Leave empty to use "
            + "the organization client scope assigned to the client, which works whenever a client has exactly one.");
        scopeName.setType(ProviderConfigProperty.STRING_TYPE);
        scopeName.setRequired(false);
        return List.of(scopeName);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return OrganizationScopeInjectorAuthenticator.INSTANCE;
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
