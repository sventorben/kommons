---
layout: default
title: Single Organization per Session
nav_order: 6
---

# 🏢 Single Organization per Session

Keycloak's organization feature lets a user be a member of several organizations at once, and by default a token can
carry all of them. This page describes how to bind **every session to exactly one organization**, so that:

- the user picks an organization at login and cannot skip the choice,
- no request can ask for two organizations or for all of them,
- no request can quietly ask for none,
- a token or assertion never names more than one organization.

Three providers work together:

| Provider | Type | Purpose |
|---|---|---|
| `kommons-orgs-scope-enforcer` | Client policy executor | Rejects requests whose `scope` does not resolve to exactly one organization |
| `kommons-orgs-scope-injector` | Authenticator | Adds the organization scope to OpenID Connect requests that did not ask for it |
| `kommons-orgs-select-organization` | Required action | Makes the user choose an organization for protocols without a `scope` parameter (SAML) |

You do not necessarily need all three. See [Which pieces do I need?](#-which-pieces-do-i-need) below.

---

## 🧭 Why more than a client policy is needed

It helps to know how Keycloak decides which organization a session belongs to, because the moving parts sit in two
different places.

**The login side reads the raw `scope` request parameter.** The organization selection screen is only shown when the
request asked for the bare `organization` scope, the user is a member of more than one enabled organization, and no
organization has been picked yet. Even the cookie authenticator consults the requested scope: when an organization
scope is present it steps aside so the organization can be resolved before the session is reused, and when it is
absent single sign-on completes without ever touching the organization flow.

**The token side reads the granted client scopes.** The `oidc-organization-membership-mapper` resolves organizations
from the scopes attached to the client session.

That split has a consequence worth spelling out, because it is the most common wrong turn:

> ⚠️ **Making the `organization` client scope a default scope does not force the selection.**
> Default scopes are visible to the token mappers but not to the login side, which only ever looks at the `scope`
> request parameter. A user with a single membership then still gets a correct token, while a user with several
> memberships gets **no organization claim at all** and is never asked to choose. The failure is silent and only hits
> multi-organization users, so it easily survives testing.
>
> Measured on Keycloak 26.7.0 with the scope assigned as **default** and a user in two organizations:
> `scope=openid` → no `organization` claim, `scope=openid organization:*` → both organizations, and
> `scope=openid organization` → **HTTP 500**. The same explicit request succeeds when the scope is assigned as
> *optional*, so keep the organization scope optional and let the injector add it to the request.

The scope injector exists precisely because rewriting the requested scope is the one change that both sides observe.

---

## 🚦 Client Policy Executor: `kommons-orgs-scope-enforcer`

Rejects a request when its `scope` parameter does not resolve to exactly one organization.

### Rules

| Requested scope | Result |
|---|---|
| `organization` | ✅ accepted — resolves to the user's only membership, or to the one they select |
| `organization:acme` | ✅ accepted (unless the selection mode forbids it, see below) |
| `organization:acme organization:globex` | ❌ rejected — more than one organization |
| `organization:*` | ❌ rejected — all organizations |
| *(no organization scope)* | ❌ rejected, unless "Require an organization scope" is switched off |

Rejections are returned as `invalid_scope` with HTTP 400.

### Where it applies

The executor runs on every event that carries a client supplied `scope` and can widen the organizations in a token:
authorization requests, pushed authorization requests, CIBA backchannel authentication, device authorization, token
refresh, token exchange, and the resource owner password credentials grant.

It deliberately does **not** run on the client credentials grant: that grant has no user and therefore no organization
membership to constrain.

An empty `scope` on a **refresh** is treated as "reuse what was granted before", not as "no organization requested",
so a normal refresh is never rejected. An explicit scope on a refresh is still checked.

### Configuration

| Option | Key | Type | Default |
|---|---|---|---|
| Require an organization scope | `kommons.orgs.require.scope` | Boolean | `true` |
| How the organization may be selected | `kommons.orgs.selection.mode` | `any-single` \| `user-selected` | `any-single` |

- **`any-single`** — the client may either request the bare `organization` scope and let the user pick, or name
  exactly one organization with `organization:<alias>`.
- **`user-selected`** — only the bare `organization` scope is accepted. The client can never pin an organization, so
  the choice always belongs to the user. Use this when clients are not trusted to choose the tenant.

The organization scope is not recognized by name but by its protocol mapper: any client scope carrying the
`oidc-organization-membership-mapper` counts, which is the same rule Keycloak applies internally. If you renamed the
scope, or defined several, they are all recognized.

### Setup

1. **Realm settings → Client policies → Profiles → Create client profile**, for example `single-organization`
2. Add the executor **Organization Scope Enforcer** and configure it
3. **Policies → Create client policy**, add the condition **any-client** and attach the profile

Or import it with your realm:

```json
{
  "clientProfiles": {
    "profiles": [
      {
        "name": "single-organization",
        "executors": [
          {
            "executor": "kommons-orgs-scope-enforcer",
            "configuration": {
              "kommons.orgs.require.scope": true,
              "kommons.orgs.selection.mode": "any-single"
            }
          }
        ]
      }
    ]
  },
  "clientPolicies": {
    "policies": [
      {
        "name": "single-organization-for-all-clients",
        "enabled": true,
        "conditions": [{ "condition": "any-client", "configuration": {} }],
        "profiles": ["single-organization"]
      }
    ]
  }
}
```

---

## 💉 Authenticator: `kommons-orgs-scope-injector`

Adds the organization scope to an OpenID Connect authorization request that did not ask for it, so that the selection
screen is triggered and the organization ends up in the token — **without having to change any client**.

Use it when you cannot make every application request the `organization` scope. With the injector in place you can
leave the executor's "Require an organization scope" enabled and still never reject a legacy client, because by the
time the request is evaluated the scope is there.

### Behaviour

- Runs only for the `openid-connect` protocol. SAML has no `scope` parameter; use the required action instead.
- Does nothing when the request already contains an organization scope, so a client that asks for
  `organization:acme` keeps its choice.
- Never authenticates anybody. It rewrites the requested scope and reports `attempted`, letting the flow continue.
- Failures are logged and swallowed: a problem here can never block a login, the request simply behaves as before.

### Configuration

| Option | Key | Type | Default |
|---|---|---|---|
| Organization scope name | `kommons.orgs.scope.name` | String | *(empty)* |

Leave it empty to use the organization client scope assigned to the client, which works whenever a client has exactly
one. Set it explicitly if a client has several organization client scopes assigned, otherwise nothing is injected and
a warning is logged.

### Setup

1. **Authentication → Flows**, duplicate the **browser** flow (built-in flows cannot be edited)
2. **Add step → Organization Scope Injector**
3. Move it to the **top of the flow**, above **Cookie**
4. Set the requirement to **Alternative**
5. Bind the flow: **Action → Bind flow → Browser flow**

> ⚠️ **The step must be `Alternative` and must sit before `Cookie`.**
> The injector must run before the cookie authenticator decides whether to complete single sign-on. It is also the
> reason `Required` is not offered as a requirement: a required execution disables every alternative of its parent
> flow, which would break cookie based single sign-on entirely.

The organization client scope still has to be assigned to the client, as optional or default. The injector adds the
scope to the request, it does not grant a scope the client is not allowed to use.

---

## 🧑‍💼 Required Action: `kommons-orgs-select-organization`

Makes the user pick exactly one organization for login protocols that have no `scope` parameter, above all **SAML**.

Keycloak's own selection screen is driven by the organization scope and is therefore unreachable for a SAML client.
Required actions on the other hand are evaluated on **every** authentication regardless of protocol, including a login
that reuses an existing single sign-on session, which makes this the one hook that cannot be bypassed.

### Behaviour

- Skips OpenID Connect entirely — that is covered by Keycloak's organization authenticator together with the
  injector. Without this guard the user would be asked twice.
- Skips when an organization has already been selected for the client session.
- Selects silently when the user is a member of exactly one enabled organization.
- Shows the selection screen when the user is a member of several, reusing Keycloak's own `select-organization.ftl`
  from the base login theme, so no custom theme is needed.
- Validates the submitted alias against the user's enabled memberships before accepting it.

The chosen organization is written to the `kc.org` client note — the very note Keycloak's browser flow uses. It
reaches the authenticated client session through Keycloak's regular note transfer, which is protocol independent.

### Setup

**Authentication → Required actions**, then set **Select Organization** to *Enabled*.

Leave **Set as default action** **off**. The action is triggered by its own evaluation on every login, not by being
assigned to users.

### Reading the selection in a SAML mapper

Keycloak's built-in SAML organization mapper (`saml-organization-membership-mapper`) writes **every** organization the
user belongs to as a multi-valued attribute. It ignores the selection completely. To emit a single organization you
need your own `SAMLAttributeStatementMapper`:

```java
@Override
public void transformAttributeStatement(AttributeStatementType statement, ProtocolMapperModel model,
        KeycloakSession session, UserSessionModel userSession, AuthenticatedClientSessionModel clientSession) {

    String organizationId = clientSession.getNote(OrganizationModel.ORGANIZATION_ATTRIBUTE);
    if (organizationId == null) {
        return; // fail closed: no selection means no organization attribute
    }
    // resolve the organization, re-check isEnabled() and isMember(user), then add a single attribute value
}
```

Two things to get right:

- **Fail closed on a missing note.** Do not fall back to "all memberships", or the one path that bypassed the
  selection silently produces the multi-organization assertion you are trying to prevent.
- **Do not read `session.getContext().getOrganization()`.** It is only populated when Keycloak's organization
  authenticator actually ran, which is not the case on the single sign-on path nor when this required action made the
  choice.

> ⚠️ Remove the built-in SAML organization mapper from those clients. If both are attached, the assertion contains
> your single value **and** the full list.

---

## 🧩 Which pieces do I need?

| Situation | Enforcer | Injector | Required action |
|---|---|---|---|
| OIDC only, all clients request `organization` | ✅ | — | — |
| OIDC only, clients cannot be changed | ✅ | ✅ | — |
| SAML clients involved | ✅ (no effect on SAML) | ✅ for the OIDC clients | ✅ |

The enforcer is the only piece that rejects anything, and it has no effect on SAML: SAML has no `scope` parameter, so
there is nothing for it to validate.

---

## ⚠️ Limitations

**The choice is per client session, not per single sign-on session.** Keycloak stores the selected organization in a
client note. Two clients in one single sign-on session can therefore end up on two different organizations. Each
individual token still names exactly one organization, and the user chose deliberately both times, so "one
organization per token" holds. If your requirement is stricter and means one organization for the whole single
sign-on session, you need to pin the selection on the user session — note that doing so also removes the ability to
switch organizations without a full logout.

**Switching organizations means a new authorization request.** Because a new authorization request starts a fresh
authentication session whose client notes are empty, a user who is a member of several organizations is asked again.
That is the switching mechanism; no logout is required.

**Disabled organizations may appear on the selection screen.** The reused `select-organization.ftl` lists the user's
memberships without filtering by enabled state. This matches Keycloak's own behaviour. A disabled organization is
rejected on submit.

**The client credentials grant is out of scope.** Service accounts have no organization membership, so nothing is
enforced there.

---

## 🧪 Verifying the setup

**Rejection rules** can be checked without a browser using the resource owner password credentials grant:

```bash
# rejected: two organizations
curl -d grant_type=password -d client_id=my-client -d username=alice -d password=... \
     -d 'scope=openid organization:acme organization:globex' \
     https://<host>/realms/<realm>/protocol/openid-connect/token
# => 400 {"error":"invalid_scope", ...}

# rejected: all organizations
... -d 'scope=openid organization:*'

# accepted
... -d 'scope=openid organization:acme'
```

**The selection screen** needs a browser login with a user who is a member of more than one enabled organization.
With the injector active, a client that requests no scope at all should still land on the organization selection.

**The resulting claim** is best inspected in **Clients → *your client* → Client scopes → Evaluate**.
