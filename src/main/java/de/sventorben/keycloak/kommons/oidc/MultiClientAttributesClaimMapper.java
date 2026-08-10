package de.sventorben.keycloak.kommons.oidc;

import org.jboss.logging.Logger;
import org.keycloak.models.*;
import org.keycloak.models.utils.MapperTypeSerializer;
import org.keycloak.protocol.ProtocolMapperConfigException;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper.INCLUDE_IN_INTROSPECTION;

public final class MultiClientAttributesClaimMapper extends AbstractOIDCProtocolMapper
    implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper, TokenIntrospectionTokenMapper {

    private static final Logger LOG = Logger.getLogger(MultiClientAttributesClaimMapper.class);

    public static final String PROVIDER_ID = "kommons-client-attributes-claim-mapper";

    static final String CLAIM_MAPPINGS_CONFIG = "kommons.client.attr.claims";

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getDisplayType() {
        return "Multi Client Attributes Claims Mapper";
    }

    @Override
    public String getHelpText() {
        return "Maps client attributes as claims into the token. Configure a claim name for every client attribute you want to expose. Each attribute value is looked up on the requesting client and added under the claim name it is keyed by.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>();
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(properties, MultiClientAttributesClaimMapper.class);

        properties.add(new ProviderConfigProperty(
            CLAIM_MAPPINGS_CONFIG,
            "Claims",
            "Claim name on the left, the client attribute whose value it carries on the right.",
            ProviderConfigProperty.MAP_TYPE,
            null
        ));

        return properties;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void validateConfig(KeycloakSession session, RealmModel realm, ProtocolMapperContainerModel client, ProtocolMapperModel mapperModel) throws ProtocolMapperConfigException {
        for (Map.Entry<String, List<String>> entry : rawMappings(mapperModel).entrySet()) {
            String claimName = trimmed(entry.getKey());
            List<String> attrNames = entry.getValue().stream()
                .map(MultiClientAttributesClaimMapper::trimmed)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();

            if (claimName.isEmpty()) {
                throw new ProtocolMapperConfigException("Claim name must not be empty.");
            }
            if (attrNames.isEmpty()) {
                throw new ProtocolMapperConfigException(
                    "Claim '" + claimName + "' has no client attribute name.");
            }
            if (attrNames.size() > 1) {
                throw new ProtocolMapperConfigException(
                    "Claim '" + claimName + "' is mapped to more than one client attribute: " + String.join(", ", attrNames));
            }
        }
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession, KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        Map<String, String> claimMappings = parseClaimMappings(mappingModel);
        if (claimMappings.isEmpty()) {
            return;
        }

        ClientModel client = clientSessionCtx.getClientSession().getClient();

        claimMappings.forEach((claimName, attrName) -> {
            String attrValue = client.getAttribute(attrName);

            if (attrValue == null) {
                LOG.debugf("Mapper '%s': client attribute '%s' not found on client '%s' in realm '%s', skipping claim '%s'.",
                    mappingModel.getName(), attrName, client.getClientId(), client.getRealm().getName(), claimName);
                return;
            }

            String claimType = deriveClaimType(attrValue);
            ProtocolMapperModel perClaimModel = HardcodedClaim.create(mappingModel.getName(), claimName, attrValue, claimType,
                OIDCAttributeMapperHelper.includeInAccessToken(mappingModel), OIDCAttributeMapperHelper.includeInIDToken(mappingModel), OIDCAttributeMapperHelper.includeInIntrospection(mappingModel));
            perClaimModel.getConfig().putIfAbsent(INCLUDE_IN_INTROSPECTION, Boolean.toString(OIDCAttributeMapperHelper.includeInIntrospection(mappingModel)));
            OIDCAttributeMapperHelper.mapClaim(token, perClaimModel, attrValue);
        });
    }

    /**
     * The configured claim names mapped to the client attribute each one takes its value from. Blank entries are
     * dropped rather than rejected, so a half-filled row in the Admin Console cannot produce an empty claim name;
     * {@link #validateConfig} is what reports those back to the administrator.
     */
    static Map<String, String> parseClaimMappings(ProtocolMapperModel model) {
        Map<String, String> claimMappings = new LinkedHashMap<>();
        rawMappings(model).forEach((claimName, attrNames) -> {
            String claim = trimmed(claimName);
            if (claim.isEmpty()) {
                return;
            }
            attrNames.stream()
                .map(MultiClientAttributesClaimMapper::trimmed)
                .filter(attrName -> !attrName.isEmpty())
                .findFirst()
                .ifPresent(attrName -> claimMappings.put(claim, attrName));
        });
        return claimMappings;
    }

    /**
     * Keycloak stores a {@link ProviderConfigProperty#MAP_TYPE} property as a JSON list of key/value pairs, and
     * groups repeated keys into a list of values.
     */
    private static Map<String, List<String>> rawMappings(ProtocolMapperModel model) {
        Map<String, String> config = model.getConfig();
        if (config == null) {
            return Map.of();
        }
        return MapperTypeSerializer.deserialize(config.get(CLAIM_MAPPINGS_CONFIG));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    static String deriveClaimType(String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return "boolean";
        }
        try {
            Integer.parseInt(value);
            return "int";
        } catch (NumberFormatException ignored) {
        }
        try {
            Long.parseLong(value);
            return "long";
        } catch (NumberFormatException ignored) {
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return "JSON";
        }
        return "String";
    }
}
