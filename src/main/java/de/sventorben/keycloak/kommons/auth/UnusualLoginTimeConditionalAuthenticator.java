package de.sventorben.keycloak.kommons.auth;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.time.Clock;
import java.time.LocalTime;

public final class UnusualLoginTimeConditionalAuthenticator implements ConditionalAuthenticator {

    private static final Logger LOG = Logger.getLogger(UnusualLoginTimeConditionalAuthenticator.class);

    private final Clock clock;

    public UnusualLoginTimeConditionalAuthenticator() {
        this(Clock.systemUTC());
    }

    /**
     * Login times are recorded in UTC, so the clock has to report UTC as well.
     */
    UnusualLoginTimeConditionalAuthenticator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean matchCondition(AuthenticationFlowContext context) {

        UnusualLoginTimeConditionalAuthenticatorConfig config = new UnusualLoginTimeConditionalAuthenticatorConfig(context.getAuthenticatorConfig());
        int skew = config.getSkew();

        if (context.getUser() == null) {
            LOG.debug("Skipping authenticator execution with id '%s' - no user logged in".formatted(context.getExecution().getId()));
            return false;
        }

        UnusualLoginTimeUserWrapper user = new UnusualLoginTimeUserWrapper(context.getUser());
        return !isInRange(user, skew);
    }

    private boolean isInRange(UnusualLoginTimeUserWrapper user, int skew) {
        if (!user.hasRecordedLoginTimes()) {
            // Nothing has been recorded yet, so no time can be unusual. Applying the skew to the MIN..MAX default
            // would wrap it around midnight and mark almost every login as unusual.
            return true;
        }

        LocalTime adjustedStart = user.getMinTime().minusMinutes(skew);
        LocalTime adjustedEnd = user.getMaxTime().plusMinutes(skew);
        LocalTime loginTime = LocalTime.now(clock);
        return isInRange(adjustedStart, adjustedEnd, loginTime);
    }

    private static boolean isInRange(LocalTime start, LocalTime end, LocalTime loginTime) {
        boolean isInRange;
        // Wraparound logic:
        if (!start.isAfter(end)) {
            // Normal case (e.g., 08:00–20:00)
            isInRange = !(loginTime.isBefore(start) || loginTime.isAfter(end));
        } else {
            // Wraparound case (e.g., 23:00–01:00)
            isInRange = !(loginTime.isBefore(start) && loginTime.isAfter(end));
        }
        return isInRange;
    }

    @Override
    public void action(AuthenticationFlowContext authenticationFlowContext) {

    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel) {

    }

    @Override
    public void close() {

    }
}
