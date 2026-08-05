package de.sventorben.keycloak.kommons.orgs;

import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.OrganizationProvider;

import java.util.List;
import java.util.stream.Stream;

import static de.sventorben.keycloak.kommons.orgs.OrganizationGroups.Label.NAME;
import static de.sventorben.keycloak.kommons.orgs.OrganizationGroups.Label.PATH;
import static de.sventorben.keycloak.kommons.orgs.OrganizationGroups.Source.AUTO;
import static de.sventorben.keycloak.kommons.orgs.OrganizationGroups.Source.CONVENTION;
import static de.sventorben.keycloak.kommons.orgs.OrganizationGroups.Source.ORGANIZATION_GROUPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code source} x {@code label} x {@code prefix} matrix of the organization aware group mapper.
 *
 * <p>The flattening dimension sits in the mapper itself and is covered end-to-end by {@code OidcOrgGroupsIT}.
 */
class OrganizationGroupsTest {

    private static final String ORGANIZATION_ID = "org-id";
    private static final String ALIAS = "acme";

    private final UserModel user = mock(UserModel.class);

    // --- configuration parsing -------------------------------------------------------------------------------

    @Test
    void sourceDefaultsToAutoAndToleratesGarbage() {
        assertThat(OrganizationGroups.Source.from(null)).isEqualTo(AUTO);
        assertThat(OrganizationGroups.Source.from("  ")).isEqualTo(AUTO);
        assertThat(OrganizationGroups.Source.from("nonsense")).isEqualTo(AUTO);
        assertThat(OrganizationGroups.Source.from("organization-groups")).isEqualTo(ORGANIZATION_GROUPS);
        assertThat(OrganizationGroups.Source.from("CONVENTION")).isEqualTo(CONVENTION);
    }

    @Test
    void labelDefaultsToNameAndToleratesGarbage() {
        assertThat(OrganizationGroups.Label.from(null)).isEqualTo(NAME);
        assertThat(OrganizationGroups.Label.from("nonsense")).isEqualTo(NAME);
        assertThat(OrganizationGroups.Label.from("path")).isEqualTo(PATH);
    }

    @Test
    void prefixingIsPurelyTextual() {
        assertThat(prefix(false, null).apply("leads", ALIAS)).isEqualTo("leads");
        assertThat(prefix(true, null).apply("leads", ALIAS)).isEqualTo("acme_leads");
        assertThat(prefix(true, null).apply("engineering/leads", ALIAS))
            .isEqualTo("acme_engineering/leads");
    }

    @Test
    void theSeparatorIsConfigurable() {
        assertThat(prefix(true, ":").apply("leads", ALIAS)).isEqualTo("acme:leads");
        assertThat(prefix(true, "::").apply("leads", ALIAS)).isEqualTo("acme::leads");
        assertThat(prefix(true, "/").apply("engineering/leads", ALIAS))
            .isEqualTo("acme/engineering/leads");
    }

    @Test
    void anUnsetSeparatorKeepsTheHistoricalUnderscore() {
        // The admin console cannot distinguish "unset" from "empty", so blank must not silently concatenate.
        assertThat(prefix(true, null).apply("leads", ALIAS)).isEqualTo("acme_leads");
        assertThat(prefix(true, "").apply("leads", ALIAS)).isEqualTo("acme_leads");
        assertThat(prefix(true, "   ").apply("leads", ALIAS)).isEqualTo("acme_leads");
    }

    @Test
    void theSeparatorIsIgnoredWhenNotPrefixing() {
        assertThat(prefix(false, "::").apply("leads", ALIAS)).isEqualTo("leads");
    }

    // --- organization groups ---------------------------------------------------------------------------------

    @Test
    void organizationGroupsAsNames() {
        KeycloakSession session = sessionWithOrganizationGroups();

        assertThat(labels(session, ORGANIZATION_GROUPS, NAME, false)).containsExactly("sales", "leads");
    }

    @Test
    void organizationGroupsAsPrefixedNames() {
        KeycloakSession session = sessionWithOrganizationGroups();

        assertThat(labels(session, ORGANIZATION_GROUPS, NAME, true)).containsExactly("acme_sales", "acme_leads");
    }

    @Test
    void organizationGroupsAsPaths() {
        KeycloakSession session = sessionWithOrganizationGroups();

        // The nested group renders relative to its organization: Keycloak's path builder already stops at the
        // internal group that roots the organization hierarchy.
        assertThat(labels(session, ORGANIZATION_GROUPS, PATH, false)).containsExactly("sales", "engineering/leads");
    }

    @Test
    void organizationGroupsAsPrefixedPaths() {
        KeycloakSession session = sessionWithOrganizationGroups();

        assertThat(labels(session, ORGANIZATION_GROUPS, PATH, true))
            .containsExactly("acme_sales", "acme_engineering/leads");
    }

    // --- convention layout -----------------------------------------------------------------------------------

    @Test
    void conventionGroupsAsNames() {
        KeycloakSession session = sessionWithConventionGroups();

        assertThat(labels(session, CONVENTION, NAME, false)).containsExactly("developers");
    }

    @Test
    void conventionGroupsAsPrefixedNames() {
        KeycloakSession session = sessionWithConventionGroups();

        assertThat(labels(session, CONVENTION, NAME, true)).containsExactly("acme_developers");
    }

    @Test
    void conventionGroupsRenderTheSameForBothLabels() {
        // The convention layout is flat by construction, so a path cannot differ from a name.
        assertThat(labels(sessionWithConventionGroups(), CONVENTION, PATH, false)).containsExactly("developers");
        assertThat(labels(sessionWithConventionGroups(), CONVENTION, PATH, true))
            .containsExactly("acme_developers");
    }

    @Test
    void anOrganizationOutsideTheConventionTreeContributesNothing() {
        KeycloakSession session = sessionWithConventionGroups();
        OrganizationModel other = organization("globex");

        assertThat(OrganizationGroups.labelsFor(session, other, user, rendering(CONVENTION, NAME, false))).isNull();
    }

    @Test
    void aMissingConventionRootContributesNothing() {
        KeycloakSession session = session();
        when(session.groups().getGroupByName(any(), eq(null), eq(OrganizationGroups.CONVENTION_ROOT_GROUP)))
            .thenReturn(null);

        assertThat(labels(session, CONVENTION, NAME, false)).isNull();
    }

    // --- auto ------------------------------------------------------------------------------------------------

    @Test
    void autoPrefersOrganizationGroupsWhenTheOrganizationHasAny() {
        KeycloakSession session = sessionWithOrganizationGroups();

        assertThat(labels(session, AUTO, NAME, false)).containsExactly("sales", "leads");
    }

    @Test
    void autoFallsBackToTheConventionWhenTheOrganizationHasNoGroups() {
        KeycloakSession session = sessionWithConventionGroups();

        assertThat(labels(session, AUTO, NAME, false)).containsExactly("developers");
    }

    // --- guards ----------------------------------------------------------------------------------------------

    @Test
    void withoutAnOrganizationOrUserThereIsNothingToRender() {
        KeycloakSession session = sessionWithOrganizationGroups();

        assertThat(OrganizationGroups.labelsFor(session, null, user, rendering(AUTO, NAME, false))).isNull();
        assertThat(OrganizationGroups.labelsFor(session, organization(ALIAS), null, rendering(AUTO, NAME, false))).isNull();
    }

    // --- fixtures --------------------------------------------------------------------------------------------

    private List<String> labels(KeycloakSession session, OrganizationGroups.Source source,
                                OrganizationGroups.Label label, boolean prefix) {
        return OrganizationGroups.labelsFor(session, organization(ALIAS), user, rendering(source, label, prefix));
    }

    private static OrganizationGroups.AliasPrefix prefix(boolean enabled, String separator) {
        return OrganizationGroups.AliasPrefix.of(enabled, separator);
    }

    private static OrganizationGroups.Rendering rendering(OrganizationGroups.Source source,
                                                          OrganizationGroups.Label label, boolean prefixWithAlias) {
        return new OrganizationGroups.Rendering(source, label, prefix(prefixWithAlias, null));
    }

    private static OrganizationModel organization(String alias) {
        OrganizationModel organization = mock(OrganizationModel.class);
        when(organization.getId()).thenReturn(ORGANIZATION_ID);
        when(organization.getAlias()).thenReturn(alias);
        return organization;
    }

    private static KeycloakSession session() {
        RealmModel realm = mock(RealmModel.class);
        KeycloakContext context = mock(KeycloakContext.class);
        when(context.getRealm()).thenReturn(realm);

        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getContext()).thenReturn(context);
        when(session.groups()).thenReturn(mock(GroupProvider.class));
        when(session.getProvider(OrganizationProvider.class)).thenReturn(mock(OrganizationProvider.class));
        return session;
    }

    /**
     * An organization with a top level group {@code sales} and a nested group {@code engineering/leads}, both of
     * which the user is a direct member of.
     */
    private KeycloakSession sessionWithOrganizationGroups() {
        KeycloakSession session = session();
        OrganizationProvider organizations = session.getProvider(OrganizationProvider.class);

        GroupModel internal = organizationGroup(ORGANIZATION_ID, null);
        GroupModel sales = organizationGroup("sales", internal);
        GroupModel engineering = organizationGroup("engineering", internal);
        GroupModel leads = organizationGroup("leads", engineering);

        when(organizations.getTopLevelGroups(any(), anyInt(), anyInt()))
            .thenAnswer(invocation -> Stream.of(sales, engineering));
        when(organizations.getOrganizationGroupsByMember(any(), eq(user)))
            .thenAnswer(invocation -> Stream.of(sales, leads));

        return session;
    }

    /**
     * A realm using the {@code /organizations/acme/developers} convention and no organization groups at all.
     */
    private KeycloakSession sessionWithConventionGroups() {
        KeycloakSession session = session();

        when(session.getProvider(OrganizationProvider.class).getTopLevelGroups(any(), anyInt(), anyInt()))
            .thenAnswer(invocation -> Stream.empty());

        GroupModel developers = mock(GroupModel.class);
        when(developers.getName()).thenReturn("developers");
        when(user.isMemberOf(developers)).thenReturn(true);

        GroupModel aliasGroup = mock(GroupModel.class);
        when(aliasGroup.getName()).thenReturn(ALIAS);
        when(aliasGroup.getSubGroupsStream()).thenAnswer(invocation -> Stream.of(developers));

        GroupModel root = mock(GroupModel.class);
        when(root.getSubGroupsStream()).thenAnswer(invocation -> Stream.of(aliasGroup));

        when(session.groups().getGroupByName(any(), eq(null), eq(OrganizationGroups.CONVENTION_ROOT_GROUP)))
            .thenReturn(root);

        return session;
    }

    /**
     * Mocks an organization group so that Keycloak's real path builder can walk it. The builder stops at the group
     * named after the organization id, which is how a path ends up relative to its organization.
     */
    private static GroupModel organizationGroup(String name, GroupModel parent) {
        OrganizationModel organization = mock(OrganizationModel.class);
        when(organization.getId()).thenReturn(ORGANIZATION_ID);

        GroupModel group = mock(GroupModel.class);
        when(group.getName()).thenReturn(name);
        when(group.getParent()).thenReturn(parent);
        when(group.getOrganization()).thenReturn(organization);
        return group;
    }
}
