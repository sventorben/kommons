package de.sventorben.keycloak.kommons.orgs;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;

class OrganizationScopeEnforcerExecutorConfiguration extends ClientPolicyExecutorConfigurationRepresentation {

    static final String CONFIG_REQUIRE_SCOPE = "kommons.orgs.require.scope";
    static final String CONFIG_SELECTION_MODE = "kommons.orgs.selection.mode";

    /** Accept either the bare organization scope or a single {@code organization:<alias>}. */
    static final String MODE_ANY_SINGLE = "any-single";

    /** Accept only the bare organization scope, so the organization is always chosen by the user. */
    static final String MODE_USER_SELECTED = "user-selected";

    @JsonProperty(CONFIG_REQUIRE_SCOPE)
    private Boolean requireScope;

    @JsonProperty(CONFIG_SELECTION_MODE)
    private String selectionMode;

    boolean isRequireScope() {
        return Boolean.TRUE.equals(requireScope);
    }

    boolean isUserSelectedOnly() {
        return MODE_USER_SELECTED.equals(selectionMode);
    }

    /**
     * Deliberately not named {@code setRequireScope}: Jackson picks up non-public setters, which would add a second
     * property alongside the one the {@link JsonProperty} annotated field already declares.
     */
    OrganizationScopeEnforcerExecutorConfiguration withRequireScope(boolean value) {
        this.requireScope = value;
        return this;
    }

    OrganizationScopeEnforcerExecutorConfiguration withSelectionMode(String value) {
        this.selectionMode = value;
        return this;
    }

    OrganizationScopeEnforcerExecutorConfiguration parseWithDefaultValues() {
        if (requireScope == null) {
            requireScope = Boolean.TRUE;
        }
        if (selectionMode == null || selectionMode.isBlank()) {
            selectionMode = MODE_ANY_SINGLE;
        }
        return this;
    }
}
