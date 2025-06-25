package de.sventorben.keycloak.kommons.auth;

import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.HashMap;
import java.util.List;

final class UnusualLoginTimeConditionalAuthenticatorConfig {

    private static final String CONFIG_SKEW_MINUTES = "kommons.skew.minutes";
    private static final int DEFAULT_SKEW_MINUTES = 15;

    static final List<ProviderConfigProperty> PROPERTIES;

    static {
        ProviderConfigProperty skewProp = new ProviderConfigProperty();
        skewProp.setName(CONFIG_SKEW_MINUTES);
        skewProp.setLabel("Clock Skew (minutes)");
        skewProp.setType(ProviderConfigProperty.INTEGER_TYPE);
        skewProp.setHelpText("Allowable deviation in login minutes range, e.g. 15 means ±15 minutes.");
        skewProp.setDefaultValue(DEFAULT_SKEW_MINUTES);
        skewProp.setRequired(true);
        skewProp.setSecret(false);
        PROPERTIES = List.of(skewProp);
    }

    private final AuthenticatorConfigModel authenticatorConfig;

    UnusualLoginTimeConditionalAuthenticatorConfig(AuthenticatorConfigModel authenticatorConfig) {
        this.authenticatorConfig = authenticatorConfig == null ? new AuthenticatorConfigModel() : authenticatorConfig;
        if (this.authenticatorConfig.getConfig() == null) {
            this.authenticatorConfig.setConfig(new HashMap<>());
        }
    }

    int getSkew() {
        return Integer.parseInt(
            authenticatorConfig.getConfig()
                .getOrDefault(CONFIG_SKEW_MINUTES, String.valueOf(DEFAULT_SKEW_MINUTES)));
    }
}
