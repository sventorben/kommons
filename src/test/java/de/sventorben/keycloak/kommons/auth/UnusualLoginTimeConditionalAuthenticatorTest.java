package de.sventorben.keycloak.kommons.auth;

import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.UserModel;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for deciding whether a login time is unusual.
 *
 * <p>The authenticator is handed a fixed clock, so the decision is driven end to end: recorded login times and the
 * configured skew produce a range, and the login time is checked against it.
 */
class UnusualLoginTimeConditionalAuthenticatorTest {

    private static final String USUAL_LOGIN_TIMES = "kommons.usualLoginTimes";
    private static final String SKEW_MINUTES = "kommons.skew.minutes";

    // --- ordinary range, e.g. 08:00 - 20:00 ---------------------------------------------------------------------

    @Test
    void aTimeInsideAnOrdinaryRangeIsNotUnusual() {
        assertThat(isUnusualAt("12:00", 0, "08:00", "20:00")).isFalse();
    }

    @Test
    void bothEndsOfAnOrdinaryRangeCount() {
        assertThat(isUnusualAt("08:00", 0, "08:00", "20:00")).isFalse();
        assertThat(isUnusualAt("20:00", 0, "08:00", "20:00")).isFalse();
    }

    @Test
    void aTimeOutsideAnOrdinaryRangeIsUnusual() {
        assertThat(isUnusualAt("07:59", 0, "08:00", "20:00")).isTrue();
        assertThat(isUnusualAt("20:01", 0, "08:00", "20:00")).isTrue();
        assertThat(isUnusualAt("03:00", 0, "08:00", "20:00")).isTrue();
    }

    // --- range wrapping around midnight -------------------------------------------------------------------------
    // A single login at 00:10 with a skew of 15 minutes spans 23:55 - 00:25, i.e. across midnight.

    @Test
    void aTimeOnEitherSideOfMidnightIsInAWrappingRange() {
        assertThat(isUnusualAt("23:58", 15, "00:10")).isFalse();
        assertThat(isUnusualAt("00:20", 15, "00:10")).isFalse();
    }

    @Test
    void bothEndsOfAWrappingRangeCount() {
        assertThat(isUnusualAt("23:55", 15, "00:10")).isFalse();
        assertThat(isUnusualAt("00:25", 15, "00:10")).isFalse();
    }

    @Test
    void theMiddleOfTheDayIsOutsideAWrappingRange() {
        assertThat(isUnusualAt("12:00", 15, "00:10")).isTrue();
        assertThat(isUnusualAt("02:00", 15, "00:10")).isTrue();
        assertThat(isUnusualAt("23:54", 15, "00:10")).isTrue();
    }

    @Test
    void aRangeAlsoWrapsWhenTheSkewPushesTheEndPastMidnight() {
        // 23:50 with a skew of 15 minutes spans 23:35 - 00:05.
        assertThat(isUnusualAt("00:03", 15, "23:50")).isFalse();
        assertThat(isUnusualAt("00:06", 15, "23:50")).isTrue();
    }

    // --- skew ---------------------------------------------------------------------------------------------------

    @Test
    void theSkewWidensTheRangeOnBothSides() {
        assertThat(isUnusualAt("08:45", 15, "09:00")).isFalse();
        assertThat(isUnusualAt("09:15", 15, "09:00")).isFalse();
        assertThat(isUnusualAt("08:44", 15, "09:00")).isTrue();
        assertThat(isUnusualAt("09:16", 15, "09:00")).isTrue();
    }

    @Test
    void withoutSkewASingleRecordedTimeMatchesOnlyThatInstant() {
        assertThat(isUnusualAt("09:00", 0, "09:00")).isFalse();
        assertThat(isUnusualAt("09:01", 0, "09:00")).isTrue();
    }

    // --- condition ----------------------------------------------------------------------------------------------

    @Test
    void withoutAUserTheConditionDoesNotMatch() {
        AuthenticationFlowContext context = mock(AuthenticationFlowContext.class);
        AuthenticationExecutionModel execution = new AuthenticationExecutionModel();
        execution.setId("execution-id");
        when(context.getExecution()).thenReturn(execution);
        when(context.getUser()).thenReturn(null);

        assertThat(newAuthenticator().matchCondition(context)).isFalse();
    }

    @Test
    void aUserWithoutHistoryNeverLoginsAtAnUnusualTime() {
        // Without a baseline every login would otherwise be flagged, because the default MIN..MAX range wraps
        // around midnight once the skew is applied. The outcome does not depend on the time, so this is the one
        // case that can be decided on the system clock the factory hands out.
        assertThat(new UnusualLoginTimeConditionalAuthenticator().matchCondition(contextWith(mockUser()))).isFalse();
    }

    @Test
    void theAuthenticatorNeedsAUser() {
        assertThat(newAuthenticator().requiresUser()).isTrue();
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static boolean isUnusualAt(String nowUtc, int skew, String... recordedLoginTimes) {
        AuthenticationFlowContext context = contextWith(mockUser(recordedLoginTimes), skew);
        return newAuthenticator(nowUtc).matchCondition(context);
    }

    private static UnusualLoginTimeConditionalAuthenticator newAuthenticator() {
        return newAuthenticator("12:00");
    }

    private static UnusualLoginTimeConditionalAuthenticator newAuthenticator(String nowUtc) {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T" + nowUtc + ":00Z"), ZoneOffset.UTC);
        return new UnusualLoginTimeConditionalAuthenticator(clock);
    }

    private static UserModel mockUser(String... recordedLoginTimes) {
        UserModel user = mock(UserModel.class);
        // The wrapper walks the attribute more than once, so every call needs a fresh stream.
        when(user.getAttributeStream(USUAL_LOGIN_TIMES)).thenAnswer(invocation -> Stream.of(recordedLoginTimes));
        return user;
    }

    private static AuthenticationFlowContext contextWith(UserModel user) {
        return contextWith(user, 15);
    }

    private static AuthenticationFlowContext contextWith(UserModel user, int skew) {
        AuthenticatorConfigModel configModel = new AuthenticatorConfigModel();
        configModel.setConfig(Map.of(SKEW_MINUTES, String.valueOf(skew)));

        AuthenticationFlowContext context = mock(AuthenticationFlowContext.class);
        when(context.getUser()).thenReturn(user);
        when(context.getAuthenticatorConfig()).thenReturn(configModel);
        return context;
    }
}
