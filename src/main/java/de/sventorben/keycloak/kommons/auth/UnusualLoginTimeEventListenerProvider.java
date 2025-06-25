package de.sventorben.keycloak.kommons.auth;

import org.keycloak.Config;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.*;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

public final class UnusualLoginTimeEventListenerProvider implements EventListenerProviderFactory, EventListenerProvider {

    private static final String PROVIDER_ID = "kommons-unusual-login-time-listener";

    private KeycloakSession keycloakSession;

    public UnusualLoginTimeEventListenerProvider() {}

    public UnusualLoginTimeEventListenerProvider(KeycloakSession keycloakSession) {
        // Constructor with session if needed
        this.keycloakSession = keycloakSession;
    }

    @Override
    public void onEvent(Event event) {
        RealmModel realm = keycloakSession.realms().getRealm(event.getRealmId());
        UserModel user = null;
        switch (event.getType()) {
            case LOGIN:
            case IDENTITY_PROVIDER_LOGIN:
                user = keycloakSession.users().getUserById(realm, event.getUserId());
                break;
            case CLIENT_LOGIN:
                ClientModel client = keycloakSession.clients().getClientById(realm, event.getClientId());
                user = keycloakSession.users().getServiceAccount(client);
                break;
        }

        if (user == null) {
            return;
        }

        long loginTime = event.getTime();
        LocalTime loginTimeUtc = Instant.ofEpochMilli(loginTime).atZone(ZoneId.of("UTC")).toLocalTime();
        UnusualLoginTimeUserWrapper userWrapper = new UnusualLoginTimeUserWrapper(user);
        userWrapper.addSuccessfulLoginTime(loginTimeUtc);
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean b) {

    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new UnusualLoginTimeEventListenerProvider(session);
    }

    @Override
    public void init(Config.Scope config) {

    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isGlobal() {
        return true;
    }

}
