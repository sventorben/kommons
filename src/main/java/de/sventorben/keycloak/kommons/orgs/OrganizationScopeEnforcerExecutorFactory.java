package de.sventorben.keycloak.kommons.orgs;

import org.keycloak.Config;
import org.keycloak.common.Profile;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory;

import java.util.ArrayList;
import java.util.List;

import static de.sventorben.keycloak.kommons.orgs.OrganizationScopeEnforcerExecutorConfiguration.CONFIG_REQUIRE_SCOPE;
import static de.sventorben.keycloak.kommons.orgs.OrganizationScopeEnforcerExecutorConfiguration.CONFIG_SELECTION_MODE;
import static de.sventorben.keycloak.kommons.orgs.OrganizationScopeEnforcerExecutorConfiguration.MODE_ANY_SINGLE;
import static de.sventorben.keycloak.kommons.orgs.OrganizationScopeEnforcerExecutorConfiguration.MODE_USER_SELECTED;

public final class OrganizationScopeEnforcerExecutorFactory implements ClientPolicyExecutorProviderFactory {

    static final String PROVIDER_ID = "kommons-orgs-scope-enforcer";

    @Override
    public String getHelpText() {
        return "Rejects requests whose scope parameter does not resolve to exactly one organization. "
            + "Requesting all organizations (organization:*) or several organization scopes at once is refused, "
            + "and requests without any organization scope can be refused as well. "
            + "Intended for multi-tenant environments that bind every session to a single organization.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> configProperties = new ArrayList<>();

        ProviderConfigProperty requireScope = new ProviderConfigProperty();
        requireScope.setName(CONFIG_REQUIRE_SCOPE);
        requireScope.setLabel("Require an organization scope");
        requireScope.setHelpText("If enabled, requests that do not carry any organization scope are rejected. "
            + "Disable this only if some clients are allowed to obtain tokens without an organization. "
            + "Note that a refresh request without a scope parameter reuses the previously granted scopes "
            + "and is never rejected by this rule.");
        requireScope.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        requireScope.setDefaultValue(Boolean.TRUE.toString());
        requireScope.setRequired(true);
        configProperties.add(requireScope);

        ProviderConfigProperty selectionMode = new ProviderConfigProperty();
        selectionMode.setName(CONFIG_SELECTION_MODE);
        selectionMode.setLabel("How the organization may be selected");
        selectionMode.setHelpText("'" + MODE_ANY_SINGLE + "': the client may either request the bare organization "
            + "scope and let the user pick, or name exactly one organization (organization:<alias>). "
            + "'" + MODE_USER_SELECTED + "': only the bare organization scope is accepted, so the organization is "
            + "always chosen by the user and can never be pinned by the client.");
        selectionMode.setType(ProviderConfigProperty.LIST_TYPE);
        selectionMode.setOptions(List.of(MODE_ANY_SINGLE, MODE_USER_SELECTED));
        selectionMode.setDefaultValue(MODE_ANY_SINGLE);
        selectionMode.setRequired(true);
        configProperties.add(selectionMode);

        return configProperties;
    }

    @Override
    public ClientPolicyExecutorProvider<?> create(KeycloakSession keycloakSession) {
        return new OrganizationScopeEnforcerExecutor(keycloakSession);
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

    @Override
    public boolean isSupported(Config.Scope config) {
        return Profile.isFeatureEnabled(Profile.Feature.ORGANIZATION);
    }
}
