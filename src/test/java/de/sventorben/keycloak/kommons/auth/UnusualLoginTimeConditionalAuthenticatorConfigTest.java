package de.sventorben.keycloak.kommons.auth;

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for reading the clock skew of the unusual login time condition.
 */
class UnusualLoginTimeConditionalAuthenticatorConfigTest {

    private static final String CONFIG_SKEW_MINUTES = "kommons.skew.minutes";
    private static final int DEFAULT_SKEW_MINUTES = 15;

    @Test
    void anUnconfiguredAuthenticatorUsesTheDefaultSkew() {
        assertThat(config(null).getSkew()).isEqualTo(DEFAULT_SKEW_MINUTES);
    }

    @Test
    void anAuthenticatorConfigWithoutValuesUsesTheDefaultSkew() {
        assertThat(config(new AuthenticatorConfigModel()).getSkew()).isEqualTo(DEFAULT_SKEW_MINUTES);
        assertThat(config(withConfig(new HashMap<>())).getSkew()).isEqualTo(DEFAULT_SKEW_MINUTES);
    }

    @Test
    void aConfiguredSkewIsUsed() {
        assertThat(config(withConfig(Map.of(CONFIG_SKEW_MINUTES, "45"))).getSkew()).isEqualTo(45);
    }

    @Test
    void aSkewOfZeroIsHonoured() {
        // Zero must not silently fall back to the default, it means "no tolerance at all".
        assertThat(config(withConfig(Map.of(CONFIG_SKEW_MINUTES, "0"))).getSkew()).isZero();
    }

    @Test
    void aNonNumericSkewIsRejectedLoudly() {
        // Better to fail than to silently authenticate with a skew nobody configured.
        assertThatThrownBy(() -> config(withConfig(Map.of(CONFIG_SKEW_MINUTES, "soon"))).getSkew())
            .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void theSkewIsExposedAsAConfigProperty() {
        assertThat(UnusualLoginTimeConditionalAuthenticatorConfig.PROPERTIES)
            .singleElement()
            .satisfies(property -> {
                assertThat(property.getName()).isEqualTo(CONFIG_SKEW_MINUTES);
                assertThat(property.getType()).isEqualTo(ProviderConfigProperty.INTEGER_TYPE);
                assertThat(property.getDefaultValue()).isEqualTo(DEFAULT_SKEW_MINUTES);
            });
    }

    private static UnusualLoginTimeConditionalAuthenticatorConfig config(AuthenticatorConfigModel model) {
        return new UnusualLoginTimeConditionalAuthenticatorConfig(model);
    }

    private static AuthenticatorConfigModel withConfig(Map<String, String> values) {
        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setConfig(new HashMap<>(values));
        return model;
    }
}
