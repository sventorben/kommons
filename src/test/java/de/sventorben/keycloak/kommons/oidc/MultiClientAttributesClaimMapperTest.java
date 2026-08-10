package de.sventorben.keycloak.kommons.oidc;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.utils.MapperTypeSerializer;
import org.keycloak.protocol.ProtocolMapperConfigException;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.TokenIntrospectionTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static de.sventorben.keycloak.kommons.oidc.MultiClientAttributesClaimMapper.CLAIM_MAPPINGS_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * Unit tests for the claim name to client attribute map this mapper is configured with, and for the claim type it
 * derives from an attribute value.
 */
class MultiClientAttributesClaimMapperTest {

    private final MultiClientAttributesClaimMapper mapper = new MultiClientAttributesClaimMapper();

    // --- configuration parsing ----------------------------------------------------------------------------------

    @Test
    void aMissingOrEmptyMapParsesToNothing() {
        assertThat(MultiClientAttributesClaimMapper.parseClaimMappings(modelWithRawConfig(null))).isEmpty();
        assertThat(MultiClientAttributesClaimMapper.parseClaimMappings(modelWithRawConfig("[]"))).isEmpty();
    }

    @Test
    void aModelWithoutAnyConfigParsesToNothing() {
        assertThat(MultiClientAttributesClaimMapper.parseClaimMappings(new ProtocolMapperModel())).isEmpty();
    }

    @Test
    void everyClaimIsPairedWithItsClientAttribute() {
        ProtocolMapperModel model = modelOf(Map.of(
            "tenant_id", "my-app.tenant-id",
            "subscription_tier", "my-app.subscription-tier"));

        assertThat(MultiClientAttributesClaimMapper.parseClaimMappings(model))
            .containsOnly(
                entry("tenant_id", "my-app.tenant-id"),
                entry("subscription_tier", "my-app.subscription-tier"));
    }

    @Test
    void claimAndAttributeNamesAreTrimmed() {
        assertThat(MultiClientAttributesClaimMapper.parseClaimMappings(modelOf(Map.of(" tenant_id ", "  my-app.tenant-id "))))
            .containsOnly(entry("tenant_id", "my-app.tenant-id"));
    }

    @Test
    void halfFilledRowsAreDropped() {
        // A row with only one side filled in is easy to produce in the Admin Console and must not create an empty
        // claim name, nor a claim that reads an attribute with an empty name.
        ProtocolMapperModel model = modelOfPairs(
            "tenant_id", "my-app.tenant-id",
            "", "my-app.orphaned",
            "no_attribute", "   ");

        assertThat(MultiClientAttributesClaimMapper.parseClaimMappings(model))
            .containsOnly(entry("tenant_id", "my-app.tenant-id"));
    }

    // --- configuration validation -------------------------------------------------------------------------------

    @Test
    void aWellFormedMapIsAccepted() {
        ProtocolMapperModel model = modelOf(Map.of("tenant_id", "my-app.tenant-id", "tier", "my-app.tier"));

        assertThatCode(() -> mapper.validateConfig(null, null, null, model)).doesNotThrowAnyException();
    }

    @Test
    void anEmptyMapIsAccepted() {
        assertThatCode(() -> mapper.validateConfig(null, null, null, modelWithRawConfig(null)))
            .doesNotThrowAnyException();
    }

    @Test
    void aClaimWithoutANameIsRejected() {
        ProtocolMapperModel model = modelOfPairs("  ", "my-app.tenant-id");

        assertThatThrownBy(() -> mapper.validateConfig(null, null, null, model))
            .isInstanceOf(ProtocolMapperConfigException.class)
            .hasMessageContaining("Claim name must not be empty");
    }

    @Test
    void aClaimWithoutAnAttributeIsRejected() {
        ProtocolMapperModel model = modelOfPairs("tenant_id", "  ");

        assertThatThrownBy(() -> mapper.validateConfig(null, null, null, model))
            .isInstanceOf(ProtocolMapperConfigException.class)
            .hasMessageContaining("'tenant_id'")
            .hasMessageContaining("no client attribute name");
    }

    @Test
    void aClaimMappedToTwoAttributesIsRejected() {
        // The map editor does not stop an administrator from using the same claim name twice.
        ProtocolMapperModel model = modelOfPairs("tenant_id", "my-app.tenant-id", "tenant_id", "my-app.other");

        assertThatThrownBy(() -> mapper.validateConfig(null, null, null, model))
            .isInstanceOf(ProtocolMapperConfigException.class)
            .hasMessageContaining("'tenant_id'")
            .hasMessageContaining("more than one client attribute");
    }

    @Test
    void aClaimRepeatedWithTheSameAttributeIsAccepted() {
        ProtocolMapperModel model = modelOfPairs("tenant_id", "my-app.tenant-id", "tenant_id", "my-app.tenant-id");

        assertThatCode(() -> mapper.validateConfig(null, null, null, model)).doesNotThrowAnyException();
    }

    // --- claim type derivation ----------------------------------------------------------------------------------

    @Test
    void booleansAreDetectedRegardlessOfCase() {
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("true")).isEqualTo("boolean");
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("FALSE")).isEqualTo("boolean");
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("True")).isEqualTo("boolean");
    }

    @Test
    void wholeNumbersBecomeIntOrLong() {
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("42")).isEqualTo("int");
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("-7")).isEqualTo("int");
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("2147483648")).isEqualTo("long");
    }

    @Test
    void objectsAndArraysBecomeJson() {
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("{\"a\":1}")).isEqualTo("JSON");
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("[1,2]")).isEqualTo("JSON");
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("  { }  ")).isEqualTo("JSON");
    }

    @Test
    void everythingElseStaysAString() {
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("acme")).isEqualTo("String");
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("1.5")).isEqualTo("String");
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("")).isEqualTo("String");
        // Looks like JSON but is not balanced, so it must not be announced as JSON.
        assertThat(MultiClientAttributesClaimMapper.deriveClaimType("{oops")).isEqualTo("String");
    }

    // --- provider metadata --------------------------------------------------------------------------------------

    @Test
    void theMapperExposesTheClaimMapAsAMapProperty() {
        assertThat(mapper.getConfigProperties())
            .filteredOn("name", CLAIM_MAPPINGS_CONFIG)
            .singleElement()
            .extracting(ProviderConfigProperty::getType)
            .isEqualTo(ProviderConfigProperty.MAP_TYPE);
        assertThat(mapper.getId()).isEqualTo(MultiClientAttributesClaimMapper.PROVIDER_ID);
    }

    @Test
    void theMapperCoversTheUsualTokenTypes() {
        assertThat(mapper)
            .isInstanceOf(OIDCAccessTokenMapper.class)
            .isInstanceOf(OIDCIDTokenMapper.class)
            .isInstanceOf(UserInfoTokenMapper.class)
            .isInstanceOf(TokenIntrospectionTokenMapper.class);
        assertThat(mapper.getDisplayType()).isEqualTo("Multi Client Attributes Claims Mapper");
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static ProtocolMapperModel modelOf(Map<String, String> claimMappings) {
        Map<String, List<String>> asMultiMap = new LinkedHashMap<>();
        claimMappings.forEach((claim, attribute) -> asMultiMap.put(claim, List.of(attribute)));
        return modelWithRawConfig(MapperTypeSerializer.serialize(asMultiMap));
    }

    /** Builds the serialised form directly, so repeated and blank keys survive into the config. */
    private static ProtocolMapperModel modelOfPairs(String... claimThenAttribute) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < claimThenAttribute.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"key\":\"").append(claimThenAttribute[i])
                .append("\",\"value\":\"").append(claimThenAttribute[i + 1]).append("\"}");
        }
        return modelWithRawConfig(json.append(']').toString());
    }

    private static ProtocolMapperModel modelWithRawConfig(String rawConfigValue) {
        Map<String, String> config = new HashMap<>();
        if (rawConfigValue != null) {
            config.put(CLAIM_MAPPINGS_CONFIG, rawConfigValue);
        }

        ProtocolMapperModel model = new ProtocolMapperModel();
        model.setName("client-attributes");
        model.setProtocolMapper(MultiClientAttributesClaimMapper.PROVIDER_ID);
        model.setConfig(config);
        return model;
    }
}
