package de.sventorben.keycloak.kommons.auth;

import org.keycloak.Config;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

import static de.sventorben.keycloak.kommons.auth.UnusualLoginTimeConditionalAuthenticatorConfig.PROPERTIES;

public final class UnusualLoginTimeConditionalAuthenticatorFactory implements ConditionalAuthenticatorFactory {

    private static final String PROVIDER_ID = "kommons-unusual-login-time";

    private static final UnusualLoginTimeConditionalAuthenticator INSTANCE = new UnusualLoginTimeConditionalAuthenticator();

    @Override
    public UnusualLoginTimeConditionalAuthenticator getSingleton() {
        return INSTANCE;
    }

    @Override
    public String getDisplayType() {
        return "Condition - Unusual Login Time";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[]{AuthenticationExecutionModel.Requirement.REQUIRED, AuthenticationExecutionModel.Requirement.DISABLED};
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "This conditional authenticator checks if the login time is unusual based on predefined criteria. If the login time is unusual, it can trigger additional authentication steps.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return PROPERTIES;
    }

    @Override
    public void init(Config.Scope scope) {

    }

    @Override
    public void postInit(KeycloakSessionFactory keycloakSessionFactory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
