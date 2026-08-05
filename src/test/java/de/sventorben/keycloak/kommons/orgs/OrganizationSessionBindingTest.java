package de.sventorben.keycloak.kommons.orgs;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionConfigModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.Map;

import static de.sventorben.keycloak.kommons.orgs.OrganizationSessionBinding.CONFIG_BIND_TO_SSO_SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for binding a single sign-on session to one organization.
 */
class OrganizationSessionBindingTest {

    private static final String ORGANIZATION_ID = "org-id";

    @Test
    void bindingIsOffWhenTheRequiredActionIsNotRegistered() {
        RealmModel realm = mock(RealmModel.class);
        when(realm.getRequiredActionProviderByAlias(anyString())).thenReturn(null);

        assertThat(OrganizationSessionBinding.isEnabled(realm)).isFalse();
    }

    @Test
    void bindingIsOffWhenTheRequiredActionIsDisabled() {
        RealmModel realm = realmWith(false, Map.of(CONFIG_BIND_TO_SSO_SESSION, "true"));

        assertThat(OrganizationSessionBinding.isEnabled(realm)).isFalse();
    }

    @Test
    void bindingIsOffWhenUnconfiguredOrSwitchedOff() {
        assertThat(OrganizationSessionBinding.isEnabled(realmWith(true, null))).isFalse();
        assertThat(OrganizationSessionBinding.isEnabled(realmWith(true, Map.of()))).isFalse();
        assertThat(OrganizationSessionBinding.isEnabled(realmWith(true, Map.of(CONFIG_BIND_TO_SSO_SESSION, "false"))))
            .isFalse();
    }

    @Test
    void bindingIsOnWhenEnabledAndSwitchedOn() {
        RealmModel realm = realmWith(true, Map.of(CONFIG_BIND_TO_SSO_SESSION, "true"));

        assertThat(OrganizationSessionBinding.isEnabled(realm)).isTrue();
    }

    @Test
    void resolvesARecordedOrganization() {
        UserModel user = mock(UserModel.class);
        OrganizationModel organization = organization(true, user, true);

        assertThat(OrganizationSessionBinding.resolve(sessionWith(organization), ORGANIZATION_ID, user))
            .isSameAs(organization);
    }

    @Test
    void discardsABindingThatNoLongerHolds() {
        UserModel user = mock(UserModel.class);

        // No id recorded, or no user to validate against.
        assertThat(OrganizationSessionBinding.resolve(sessionWith(null), null, user)).isNull();
        assertThat(OrganizationSessionBinding.resolve(sessionWith(null), ORGANIZATION_ID, null)).isNull();
        // Organization deleted meanwhile.
        assertThat(OrganizationSessionBinding.resolve(sessionWith(null), ORGANIZATION_ID, user)).isNull();
        // Organization disabled meanwhile.
        assertThat(OrganizationSessionBinding.resolve(sessionWith(organization(false, user, true)),
            ORGANIZATION_ID, user)).isNull();
        // Membership revoked meanwhile.
        assertThat(OrganizationSessionBinding.resolve(sessionWith(organization(true, user, false)),
            ORGANIZATION_ID, user)).isNull();
    }

    @Test
    void recordsTheOrganizationOnTheUserSession() {
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        OrganizationModel organization = organization(true, mock(UserModel.class), true);

        OrganizationSessionBinding.bind(authSession, organization);

        verify(authSession).setUserSessionNote(OrganizationSessionBinding.SESSION_NOTE, ORGANIZATION_ID);
    }

    @Test
    void appliesABindingToTheClientSession() {
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        OrganizationModel organization = organization(true, mock(UserModel.class), true);

        assertThat(OrganizationSessionBinding.applyTo(authSession, organization)).isTrue();

        verify(authSession).setClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE, ORGANIZATION_ID);
    }

    @Test
    void doesNothingWithoutABinding() {
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);

        assertThat(OrganizationSessionBinding.applyTo(authSession, null)).isFalse();

        verify(authSession, never()).setClientNote(anyString(), anyString());
    }

    @Test
    void keepsAnAlreadyMatchingClientSessionUntouched() {
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(authSession.getClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE)).thenReturn(ORGANIZATION_ID);
        OrganizationModel organization = organization(true, mock(UserModel.class), true);

        assertThat(OrganizationSessionBinding.applyTo(authSession, organization)).isTrue();

        verify(authSession, never()).setClientNote(anyString(), anyString());
    }

    @Test
    void theBindingWinsOverADifferingSelection() {
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(authSession.getClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE)).thenReturn("another-org-id");
        OrganizationModel organization = organization(true, mock(UserModel.class), true);

        assertThat(OrganizationSessionBinding.applyTo(authSession, organization)).isTrue();

        verify(authSession).setClientNote(OrganizationModel.ORGANIZATION_ATTRIBUTE, ORGANIZATION_ID);
    }

    private static RealmModel realmWith(boolean requiredActionEnabled, Map<String, String> config) {
        RequiredActionProviderModel provider = new RequiredActionProviderModel();
        provider.setEnabled(requiredActionEnabled);

        RealmModel realm = mock(RealmModel.class);
        when(realm.getRequiredActionProviderByAlias(SelectOrganizationRequiredActionFactory.PROVIDER_ID))
            .thenReturn(provider);

        RequiredActionConfigModel configModel = null;
        if (config != null) {
            configModel = new RequiredActionConfigModel();
            configModel.setConfig(config);
        }
        when(realm.getRequiredActionConfigByAlias(SelectOrganizationRequiredActionFactory.PROVIDER_ID))
            .thenReturn(configModel);

        return realm;
    }

    private static OrganizationModel organization(boolean enabled, UserModel member, boolean isMember) {
        OrganizationModel organization = mock(OrganizationModel.class);
        when(organization.getId()).thenReturn(ORGANIZATION_ID);
        when(organization.isEnabled()).thenReturn(enabled);
        when(organization.isMember(member)).thenReturn(isMember);
        return organization;
    }

    private static KeycloakSession sessionWith(OrganizationModel organization) {
        OrganizationProvider provider = mock(OrganizationProvider.class);
        when(provider.getById(ORGANIZATION_ID)).thenReturn(organization);

        KeycloakSession session = mock(KeycloakSession.class);
        when(session.getProvider(OrganizationProvider.class)).thenReturn(provider);
        return session;
    }
}
