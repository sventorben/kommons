package de.sventorben.keycloak.kommons.orgs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.OAuth2Constants;
import org.keycloak.common.Profile;
import org.keycloak.models.*;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.models.utils.RepresentationToModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.organization.protocol.mappers.oidc.OrganizationMembershipMapper;
import org.keycloak.organization.protocol.mappers.oidc.OrganizationScope;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.*;
import java.util.stream.Stream;

import static org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper.JSON_TYPE;
import static org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME;

public final class OidcOrgsGroupMapperFactory extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper, TokenIntrospectionTokenMapper, EnvironmentDependentProviderFactory {

    private static final Logger LOG = Logger.getLogger(OidcOrgsGroupMapperFactory.class);

    private static final String PROVIDER_ID = "kommons-orgs-group-mapper";

    private static final String ROOT_GROUP_NAME = "organizations";

    private static final String CONFIG_PREFIX_GROUPS = "kommons.prefix.groups.with.organization";
    private static final String CONFIG_FLAT_GROUPS = "kommons.emit.flattened.group.claim";

    private static final String CLAIM_ORGANIZATION = "organization";
    private static final String CLAIM_GROUPS = "groups";

    public OidcOrgsGroupMapperFactory() {
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getDisplayType() {
        return "Organization-aware Group Mapper";
    }

    @Override
    public String getHelpText() {
        return "Adds only the groups under an organization to the token. Requires 'Organization Membership' mapper (ID: oidc-organization-membership-mapper) to be configured and 'add organization attributes' enabled.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>();
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(properties, AudienceProtocolMapper.class);

        ProviderConfigProperty prefixGroupsProp = new ProviderConfigProperty();
        prefixGroupsProp.setName(CONFIG_PREFIX_GROUPS);
        prefixGroupsProp.setLabel("Prefix group names with organization alias");
        prefixGroupsProp.setHelpText("If true, group names will be prefixed with the organization name (e.g., 'acme_developers').");
        prefixGroupsProp.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        prefixGroupsProp.setDefaultValue("false");
        prefixGroupsProp.setRequired(true);
        properties.add(prefixGroupsProp);

        ProviderConfigProperty flatGroupsProp = new ProviderConfigProperty();
        flatGroupsProp.setName(CONFIG_FLAT_GROUPS);
        flatGroupsProp.setLabel("Emit flattened group claim");
        flatGroupsProp.setHelpText("Places all group names into a top-level 'groups' claim instead of nesting them by organization.");
        flatGroupsProp.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        flatGroupsProp.setDefaultValue("false");
        prefixGroupsProp.setRequired(true);
        properties.add(flatGroupsProp);

        return properties;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    private Stream<OrganizationModel> resolveFromRequestedScopes(KeycloakSession session, UserSessionModel userSession, ClientSessionContext context) {
        String rawScopes = context.getScopeString();
        OrganizationScope scope = OrganizationScope.valueOfScope(session, rawScopes);

        if (scope == null) {
            return Stream.empty();
        }

        return scope.resolveOrganizations(userSession.getUser(), rawScopes, session);

    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession, KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        RealmModel realm = keycloakSession.getContext().getRealm();
        if (!realm.isOrganizationsEnabled()) {
            return;
        }

        String claimName = keycloakSession.getContext().getRealm().getClientScopesStream()
            .filter(scope -> scope.getProtocolMappersStream().anyMatch(mapper -> mapper.getId().equals(mappingModel.getId())))
            .flatMap(scope -> scope.getProtocolMappersStream())
            .filter(mapper -> mapper.getProtocolMapper().equals(OrganizationMembershipMapper.PROVIDER_ID))
            .findFirst()
            .map(mapper -> mapper.getConfig().get("claim.name"))
            .orElse(CLAIM_ORGANIZATION);


        final GroupModel organizations = keycloakSession.groups().getGroupByName(realm, null, ROOT_GROUP_NAME);
        if (organizations == null) {
            ClientModel client = clientSessionCtx.getClientSession().getClient();
            LOG.warnf("Root group `%s` does not exist but mapper configured in realm %s, client %", ROOT_GROUP_NAME, client.getRealm().getName(), client.getName());
            return;
        }

        String orgId = clientSessionCtx.getClientSession().getNote(OrganizationModel.ORGANIZATION_ATTRIBUTE);
        Stream<OrganizationModel> requestedOrganizations;
        requestedOrganizations = resolveRequestedOrganizations(userSession, keycloakSession, clientSessionCtx, orgId);

        final List<String> requestedOrganizationAliases = requestedOrganizations.map(OrganizationModel::getAlias).toList();

        List<GroupModel> orgGroups = organizations.getSubGroupsStream()
            .filter(tenantRootGroup -> requestedOrganizationAliases.contains(tenantRootGroup.getName())).toList();
        ObjectNode organizationClaims = new ObjectMapper().createObjectNode();
        if (token.getOtherClaims().containsKey(claimName)) {
            Object existingClaim = token.getOtherClaims().get(claimName);
            if (existingClaim != null && !((JsonNode) existingClaim).isObject()) {
                // TODO: log warning and return
                return;
            }
            organizationClaims = (ObjectNode) existingClaim;
        } else {
            token.setOtherClaims(claimName, organizationClaims);
        }

        boolean prefixGroupNames = isPrefixGroups(mappingModel);
        boolean flatGroupClaim = isFlatGroups(mappingModel);

        ArrayNode flatGroups = new ObjectMapper().createArrayNode();

        for (GroupModel orgGroup : orgGroups) {
            String orgAlias = orgGroup.getName();

            List<String> userGroupNames = orgGroup.getSubGroupsStream()
                .filter(group -> userSession.getUser().isMemberOf(group))
                .map(group -> prefixGroupNames ? orgAlias + "_" + group.getName() : group.getName())
                .toList();

            if (flatGroupClaim) {
                userGroupNames.forEach(flatGroups::add);
            } else {
                ObjectNode orgClaims = organizationClaims.has(orgAlias)
                    ? (ObjectNode) organizationClaims.get(orgAlias)
                    : new ObjectMapper().createObjectNode();

                ArrayNode groupsForOrg = new ObjectMapper().createArrayNode();
                userGroupNames.stream().map(TextNode::new).forEach(groupsForOrg::add);
                orgClaims.set(CLAIM_GROUPS, groupsForOrg);

                if (!organizationClaims.has(orgAlias)) {
                    organizationClaims.set(orgAlias, orgClaims);
                }
            }
        }

        if (flatGroupClaim && flatGroups.size() > 0) {
            token.setOtherClaims(CLAIM_GROUPS, flatGroups);
        } else if (!token.getOtherClaims().containsKey(claimName)) {
            token.setOtherClaims(claimName, organizationClaims);
        }
    }

    private Stream<OrganizationModel> resolveRequestedOrganizations(UserSessionModel userSession, KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx, String orgId) {
        Stream<OrganizationModel> requestedOrganizations;
        if (orgId == null) {
            requestedOrganizations = resolveFromRequestedScopes(keycloakSession, userSession, clientSessionCtx);
        } else {
            requestedOrganizations = Stream.of(keycloakSession.getProvider(OrganizationProvider.class).getById(orgId));
        }
        return requestedOrganizations;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return Profile.isFeatureEnabled(Profile.Feature.ORGANIZATION);
    }

    @Override
    public ProtocolMapperModel getEffectiveModel(KeycloakSession session, RealmModel realm, ProtocolMapperModel model) {

        ProtocolMapperModel effectiveModel = super.getEffectiveModel(session, realm, model);

        ProtocolMapperModel copy = RepresentationToModel.toModel(ModelToRepresentation.toRepresentation(effectiveModel));
        Map<String, String> config = Optional.ofNullable(copy.getConfig()).orElseGet(HashMap::new);

        config.put(JSON_TYPE, "JSON");

        if (isFlatGroups(copy)) {
            config.putIfAbsent(TOKEN_CLAIM_NAME, CLAIM_GROUPS);
        } else {
            config.putIfAbsent(TOKEN_CLAIM_NAME, OAuth2Constants.ORGANIZATION);
        }

        setDefaultValues(config);

        return copy;
    }

    private void setDefaultValues(Map<String, String> config) {
        for (ProviderConfigProperty property : getConfigProperties()) {
            Object defaultValue = property.getDefaultValue();
            if (defaultValue != null) {
                config.putIfAbsent(property.getName(), defaultValue.toString());
            }
        }
    }

    private boolean isPrefixGroups(ProtocolMapperModel model) {
        return Boolean.parseBoolean(model.getConfig().getOrDefault(CONFIG_PREFIX_GROUPS, Boolean.FALSE.toString()));
    }

    private boolean isFlatGroups(ProtocolMapperModel model) {
        return Boolean.parseBoolean(model.getConfig().getOrDefault(CONFIG_FLAT_GROUPS, Boolean.FALSE.toString()));
    }
}
