package de.sventorben.keycloak.kommons.orgs;

import org.jboss.logging.Logger;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.organization.OrganizationProvider;

import java.util.List;
import java.util.Locale;

/**
 * Collects the groups a user has within an organization and turns them into the labels that end up in the token.
 *
 * <p>Two layouts are supported. Keycloak's own <em>organization groups</em>
 * ({@link org.keycloak.models.GroupModel.Type#ORGANIZATION}) are the native mechanism and can be nested. The
 * <em>convention</em> layout predates them: a realm group named {@value #CONVENTION_ROOT_GROUP} holding one subgroup
 * per organization alias, whose direct children are the organization's groups.
 */
final class OrganizationGroups {

    private static final Logger LOG = Logger.getLogger(OrganizationGroups.class);

    /** Name of the realm group holding one subgroup per organization alias in the convention layout. */
    static final String CONVENTION_ROOT_GROUP = "organizations";

    /** Kept as the default so existing tokens do not change shape. */
    static final String DEFAULT_PREFIX_SEPARATOR = "_";

    private OrganizationGroups() {
    }

    /**
     * Where the groups of an organization are read from.
     */
    enum Source {

        /** Organization groups when the organization has any, otherwise the convention layout. */
        AUTO,

        /** Keycloak's organization groups only. */
        ORGANIZATION_GROUPS,

        /** The {@value #CONVENTION_ROOT_GROUP} group tree only. */
        CONVENTION;

        static Source from(String value) {
            return parse(Source.class, value, AUTO);
        }
    }

    /**
     * How a single group is rendered.
     */
    enum Label {

        /** The name of the group itself, for example {@code leads}. */
        NAME,

        /**
         * The path of the group within its organization, for example {@code engineering/leads}.
         *
         * <p>Only organization groups can be nested, so for the convention layout this is the same as
         * {@link #NAME}.
         */
        PATH;

        static Label from(String value) {
            return parse(Label.class, value, NAME);
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            LOG.warnf("Unknown %s '%s', falling back to '%s'", type.getSimpleName(), value, fallback);
            return fallback;
        }
    }

    /**
     * The labels to emit for {@code organization}.
     *
     * @return the labels, or {@code null} when the organization is not represented in the selected layout at all,
     *         which is different from being represented but having no groups for this user
     */
    static List<String> labelsFor(KeycloakSession session, OrganizationModel organization, UserModel user,
                                  Rendering rendering) {
        if (organization == null || user == null) {
            return null;
        }

        List<String> labels = switch (effectiveSource(session, organization, rendering.source())) {
            case ORGANIZATION_GROUPS -> organizationGroupLabels(session, organization, user, rendering.label());
            case CONVENTION -> conventionGroupLabels(session, organization, user);
            case AUTO -> null; // resolved away by effectiveSource
        };

        if (labels == null) {
            return null;
        }

        return labels.stream()
            .map(value -> rendering.prefix().apply(value, organization.getAlias()))
            .toList();
    }

    /**
     * How the groups of an organization should be rendered into claim values.
     *
     * @param source where the groups are read from
     * @param label  how a single group is rendered
     * @param prefix how a label is namespaced with the organization alias
     */
    record Rendering(Source source, Label label, AliasPrefix prefix) {
    }

    /**
     * Namespacing of a label with the organization alias, so that identically named groups of different
     * organizations stay distinguishable once they are flattened into a single claim.
     *
     * @param enabled   whether to prefix at all
     * @param separator what to put between alias and label; blank means {@value #DEFAULT_PREFIX_SEPARATOR}. Pick
     *                  something that cannot occur in an alias or a group name if consumers split the value again.
     */
    record AliasPrefix(boolean enabled, String separator) {

        static final AliasPrefix NONE = new AliasPrefix(false, null);

        static AliasPrefix of(boolean enabled, String separator) {
            return enabled ? new AliasPrefix(true, separator) : NONE;
        }

        String apply(String label, String alias) {
            return enabled ? alias + effectiveSeparator() + label : label;
        }

        private String effectiveSeparator() {
            return separator == null || separator.isBlank() ? DEFAULT_PREFIX_SEPARATOR : separator;
        }
    }


    /**
     * Resolves {@link Source#AUTO} per organization, so a realm can migrate one organization at a time.
     */
    private static Source effectiveSource(KeycloakSession session, OrganizationModel organization, Source source) {
        if (source != Source.AUTO) {
            return source;
        }

        boolean hasOrganizationGroups = session.getProvider(OrganizationProvider.class)
            .getTopLevelGroups(organization, 0, 1)
            .findAny()
            .isPresent();

        return hasOrganizationGroups ? Source.ORGANIZATION_GROUPS : Source.CONVENTION;
    }

    private static List<String> organizationGroupLabels(KeycloakSession session, OrganizationModel organization,
                                                        UserModel user, Label label) {
        return session.getProvider(OrganizationProvider.class)
            .getOrganizationGroupsByMember(organization, user)
            .map(group -> Label.PATH == label ? organizationGroupPath(group) : group.getName())
            .toList();
    }

    /**
     * The path of an organization group relative to its organization.
     *
     * <p>Keycloak's own path builder already stops at the internal group that roots an organization's hierarchy, so
     * only the leading separator has to go.
     */
    private static String organizationGroupPath(GroupModel group) {
        String path = ModelToRepresentation.buildGroupPath(group);
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Groups below {@code /organizations/<alias>}. Returns {@code null} when the organization has no such group,
     * mirroring what this mapper has always done: an organization missing from the tree contributes no claim at all.
     */
    private static List<String> conventionGroupLabels(KeycloakSession session, OrganizationModel organization,
                                                      UserModel user) {
        RealmModel realm = session.getContext().getRealm();
        GroupModel root = session.groups().getGroupByName(realm, null, CONVENTION_ROOT_GROUP);

        if (root == null) {
            LOG.debugf("Root group '%s' does not exist, no convention groups for organization '%s'",
                CONVENTION_ROOT_GROUP, organization.getAlias());
            return null;
        }

        GroupModel organizationGroup = root.getSubGroupsStream()
            .filter(group -> organization.getAlias().equals(group.getName()))
            .findFirst()
            .orElse(null);

        if (organizationGroup == null) {
            return null;
        }

        return organizationGroup.getSubGroupsStream()
            .filter(user::isMemberOf)
            .map(GroupModel::getName)
            .toList();
    }
}
