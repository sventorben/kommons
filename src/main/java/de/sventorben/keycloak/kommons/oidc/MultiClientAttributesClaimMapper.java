package de.sventorben.keycloak.kommons.oidc;

import org.jboss.logging.Logger;
import org.keycloak.models.*;
import org.keycloak.protocol.ProtocolMapperConfigException;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.keycloak.models.Constants.CFG_DELIMITER;
import static org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper.INCLUDE_IN_INTROSPECTION;

public final class MultiClientAttributesClaimMapper extends AbstractOIDCProtocolMapper
    implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper, TokenIntrospectionTokenMapper {

    private static final Logger LOG = Logger.getLogger(MultiClientAttributesClaimMapper.class);

    public static final String PROVIDER_ID = "kommons-client-attributes-claim-mapper";

    private static final String CLAIM_NAMES_CONFIG = "kommons.client.attr.claim.names";
    private static final String CLIENT_ATTR_NAMES_CONFIG = "kommons.client.attr.attribute.names";

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
        return "Maps client attributes as claims into the token. Configure two parallel lists: claim names and client attribute names. Each attribute value is looked up on the requesting client and added as the corresponding claim.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>();
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(properties, MultiClientAttributesClaimMapper.class);

        properties.add(new ProviderConfigProperty(
            CLAIM_NAMES_CONFIG,
            "Claim names",
            "Ordered list of claim names to add to the token.",
            ProviderConfigProperty.MULTIVALUED_STRING_TYPE,
            null
        ));

        properties.add(new ProviderConfigProperty(
            CLIENT_ATTR_NAMES_CONFIG,
            "Client attribute names",
            "Ordered list of client attribute names whose values are used as claim values. Must have the same length as the claim names list.",
            ProviderConfigProperty.MULTIVALUED_STRING_TYPE,
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
        List<String> claimNames = parseList(mapperModel, CLAIM_NAMES_CONFIG);
        List<String> attrNames = parseList(mapperModel, CLIENT_ATTR_NAMES_CONFIG);
        if (claimNames.size() != attrNames.size()) {
            throw new ProtocolMapperConfigException(
                "Claim names list (size " + claimNames.size() + ") and client attribute names list (size " + attrNames.size() + ") must have the same length."
            );
        }
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession, KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        List<String> claimNames = parseList(mappingModel, CLAIM_NAMES_CONFIG);
        List<String> attrNames = parseList(mappingModel, CLIENT_ATTR_NAMES_CONFIG);

        if (claimNames.size() != attrNames.size()) {
            LOG.warnf("Mapper '%s': claim names list size (%d) != attribute names list size (%d). Skipping. (mapper id: %s, realm: %s)",
                mappingModel.getName(), claimNames.size(), attrNames.size(), mappingModel.getId(), userSession.getRealm().getName());
            return;
        }

        ClientModel client = clientSessionCtx.getClientSession().getClient();

        for (int i = 0; i < claimNames.size(); i++) {
            String claimName = claimNames.get(i);
            String attrName = attrNames.get(i);
            String attrValue = client.getAttribute(attrName);

            if (attrValue == null) {
                LOG.debugf("Mapper '%s': client attribute '%s' not found on client '%s' in realm '%s', skipping claim '%s'.",
                    mappingModel.getName(), attrName, client.getClientId(), client.getRealm().getName(), claimName);
                continue;
            }

            String claimType = deriveClaimType(attrValue);
            ProtocolMapperModel perClaimModel = HardcodedClaim.create(mappingModel.getName(), claimName, attrValue, claimType,
                OIDCAttributeMapperHelper.includeInAccessToken(mappingModel), OIDCAttributeMapperHelper.includeInIDToken(mappingModel), OIDCAttributeMapperHelper.includeInIntrospection(mappingModel));
            perClaimModel.getConfig().putIfAbsent(INCLUDE_IN_INTROSPECTION, Boolean.toString(OIDCAttributeMapperHelper.includeInIntrospection(mappingModel)));
            OIDCAttributeMapperHelper.mapClaim(token, perClaimModel, attrValue);
        }
    }

    private static List<String> parseList(ProtocolMapperModel model, String configKey) {
        String raw = model.getConfig().get(configKey);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(CFG_DELIMITER))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    }

    private static String deriveClaimType(String value) {
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
