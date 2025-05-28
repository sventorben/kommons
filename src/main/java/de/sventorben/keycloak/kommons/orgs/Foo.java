package de.sventorben.keycloak.kommons.orgs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.keycloak.Config;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.ModelValidationException;

public class Foo implements EventListenerProviderFactory, EventListenerProvider {

    private EventListenerTransaction tx = new EventListenerTransaction(this::checkSyntax, null);
    private KeycloakSession session;

    public Foo() {
        // for factory
    }

    public Foo(KeycloakSession session) {
        this.session = session;
        session.getTransactionManager().enlistPrepare(tx);
    }

    @Override
    public void onEvent(Event event) {
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean b) {
        tx.addAdminEvent(adminEvent, true);
    }

    private void checkSyntax(AdminEvent adminEvent, boolean b) {
        //runJobInTransaction(sessionFactory, new KeycloakSessionTask() {
        //    @Override
        //    public void run(KeycloakSession session) {
                if (adminEvent.getResourceType() == ResourceType.GROUP) {
                    if (adminEvent.getOperationType() == OperationType.CREATE || adminEvent.getOperationType() == OperationType.UPDATE) {
                        try {
                            JsonNode jsonNode = new ObjectMapper().readTree(adminEvent.getRepresentation());
                            String groupName = jsonNode.get("name").textValue();
                            String expectedGroupPrefix = adminEvent.getRealmName() + "_";
                            if (!groupName.startsWith(expectedGroupPrefix)) {
                                throw new WebApplicationException("Group name needs tenant prefix", new ModelValidationException("Group name needs tenant prefix " + expectedGroupPrefix));
                            }
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
        //    }
        //});
    }

    @Override
    public EventListenerProvider create(KeycloakSession keycloakSession) {
        return new Foo(keycloakSession);
    }

    @Override
    public void init(Config.Scope scope) {

    }

    @Override
    public void postInit(KeycloakSessionFactory keycloakSessionFactory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return "enforce-group-prefix";
    }

    @Override
    public boolean isGlobal() {
        return true;
    }
}
