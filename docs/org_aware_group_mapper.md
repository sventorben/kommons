---
layout: default
title: Organization-aware Group Mapper
nav_order: 3
---

# 🧩 Organization-aware Group Mapper

The **Organization-aware Group Mapper** is a Keycloak protocol mapper that injects **organization-scoped group information** into OpenID Connect tokens. It allows multi-tenant applications to include only the group memberships relevant to the current organization.

This mapper is designed to solve a key limitation in Keycloak: while organizations are supported, there’s no native mechanism to **limit group claims to a specific organization**. This mapper bridges that gap using group hierarchy as a convention.

---

## 🛠️ What It Does

When a user logs in, this mapper:

- Identifies the **current organization** (via session note or OAuth scope)
- Locates the corresponding **top-level group** under the root `organizations` group
- Collects all **child groups** that the user belongs to within that organization
- Adds them to the token under a claim like:

```json
"organization": {
    "acme": {
      "groups": ["developers", "admins"]
    }
}
```

Only groups within the resolved organization are included. Groups from other tenants or outside the `organizations` hierarchy are excluded.

---

## 🎯 Use Cases

- ✅ **Multi-tenant applications** that need tenant-isolated tokens
- ✅ APIs that authorize based on scoped groups
- ✅ Clean, minimal tokens that avoid leaking irrelevant group info
- ❌ Not intended for generic group injection — Keycloak already provides that

---

## ⚙️ Requirements

To function properly, the mapper expects:

1. The **organization feature** enabled in your Keycloak instance
2. Groups organized in one of the two layouts described below

---

## 📚 Where groups come from

Keycloak did not always have organization groups. This mapper therefore understands **two layouts**.

### Organization groups (native)

Groups created under an organization, which Keycloak stores as groups of type `ORGANIZATION`. They can be
**nested**:

```
acme (organization)
├── sales
└── engineering
    └── leads
```

### The group tree convention (legacy)

A realm group named `organizations` holding one subgroup per organization alias, whose direct children are that
organization's groups. This is what the mapper supported before Keycloak had organization groups:

```
organizations/
├── acme/
│   ├── developers
│   └── admins
├── globex/
│   ├── users
```

Only the **direct children** of the alias group are read, so this layout is flat by construction.

### 🔧 Setting

| Field | Value |
|---|---|
| **Option** | `Where to read groups from` |
| **Property Key** | `kommons.orgs.group.source` |
| **Type** | `auto` \| `organization-groups` \| `convention` |
| **Default** | `auto` |

`auto` uses organization groups when the organization has any, and otherwise falls back to the convention tree. It
is resolved **per organization**, so a realm can be migrated one organization at a time — during the migration a
single token may well carry native groups for one organization and convention groups for another.

An organization that is not represented in the selected layout contributes no claim at all, which is different from
being represented but having no groups for this user (that yields an empty list).

---

## 🏷️ How a group is rendered

| Field | Value |
|---|---|
| **Option** | `How to render a group` |
| **Property Key** | `kommons.orgs.group.label` |
| **Type** | `name` \| `path` |
| **Default** | `name` |

- **`name`** — the group's own name, e.g. `leads`. This is what the mapper has always emitted.
- **`path`** — the group's path *within its organization*, e.g. `engineering/leads`.

The path is relative to the organization, never absolute: Keycloak roots each organization's hierarchy in an
internal group named after the organization's **ID**, and that segment is stripped. You will never see a UUID in the
claim.

Since the convention layout is flat, `path` and `name` produce identical output for it. The setting only makes a
difference for nested organization groups.

### ⚠️ Recommended Configuration: Organization Membership Mapper

The Organization-aware Group Mapper is designed to **work in cooperation** with Keycloak’s built-in **Organization Membership Mapper** (`oidc-organization-membership-mapper`).

To ensure group resolution works correctly, make sure the following settings are applied to the Organization Membership Mapper:

| Setting                  | Value     |
|--------------------------|-----------|
| **Claim JSON Type**      | `JSON`    |
| **Multivalued**          | `true`    |

These settings ensure that the organization context is available in a format the Organization-aware Group Mapper can interpret during token mapping.

If not set:

- The `organization` attribute may not be correctly written
- Group scoping behavior may silently fail
- The token might not include expected group claims

> 💡 Make sure this mapper is configured and appears **before** the Orgs Group Mapper in your mapper list for the client or client scope.


## 🔧 Configuration in Admin Console

1. Go to your **Client** in the Keycloak Admin Console
2. Navigate to **Client Scopes** or **Protocol Mappers**
3. Click **Create** and fill out the form as follows:

   | Field                    | Value                                          |
   |--------------------------|------------------------------------------------|
   | **Name**                | `Organization-aware Group Mapper`               |
   | **Mapper Type**         | `Organization-scoped Group Mapper`              |
   | **Add to ID token**     | ✅                                              |
   | **Add to access token** | ✅                                              |
   | **Add to userinfo**     | ✅                                              |

4. Click **Save**

⚠️ **Important:** This mapper must appear *after* the `oidc-organization-membership-mapper` in the list so that the organization context is already available.

---

## 🧪 Testing the Mapper with Keycloak

You can verify the behavior of the Organization-aware Group Mapper without writing any client code by using Keycloak’s built-in **Token Evaluation** tool.

### 🔍 Steps to Evaluate the Token Output

1. Go to the **Keycloak Admin Console**
2. In the left-hand menu, select **Clients** → your client
3. Click on the **Client scopes** → **Evaluate** tab
4. Select a **User**
6. Inspect the generated **Access Token** or **ID Token**

Look for a structured claim like this:

```json
{
  "organization": {
    "acme": {
      "groups": ["developers", "admins"]
    }
  }
}
```

### ✅ What to Check

- The `organization` claim exists
- It contains only the **groups within the resolved organization**
- Groups from other organizations or outside the `organizations` hierarchy are not included

### ⚠️ If the claim is missing

Check the following:

- The user is a member of groups that are **nested under an organization group**
- The `Organization Membership` mapper is configured and comes **before** this mapper
- The `organizations` root group exists in your realm
- Your client is configured to request a scope that includes organization data (if using scope-based resolution)

---

This method is preferred over manually logging in or decoding tokens from live flows — it gives you immediate, observable feedback inside the Keycloak UI.

## ⚙️ Prefix Group Names with Organization Alias

This mapper supports an optional configuration to **prefix group names** with their organization alias in the token.

### 🔑 Setting

| Field                                      | Value                                        |
|-------------------------------------------|----------------------------------------------|
| **Option**                                | `Prefix group names with organization alias` |
| **Property Key**                          | `kommons.prefix.groups.with.organization`    |
| **Type**                                  | Boolean                                      |
| **Default**                               | `false`                                      |

---

### 🧭 Purpose

In multi-organization scenarios, different organizations might use the same group names (e.g., `admins`, `developers`). To avoid conflicts or ambiguity in token consumers (like APIs or authorization layers), this option ensures that all group names are **namespaced** by their organization.

---

### 🧪 Example

#### 🔹 Without Prefix (default)

```json
{
  "organization": {
    "acme": {
      "groups": ["developers", "admins"]
    }
  }
}
```

#### 🔸 With Prefix Enabled

```json
{
  "organization": {
    "acme": {
      "groups": ["acme_developers", "acme_admins"]
    }
  }
}
```

This makes it easier to match and enforce tenant-specific roles or permissions downstream, especially in token-aware services.

---

### ✅ When to Enable It

You should consider enabling this option if:

- Your realm allows **duplicate group names** across organizations
- Your **APIs or permissions systems** rely on unique group identifiers
- You want to enforce clear namespacing between tenants in token data

---

### 🔣 Choosing the separator

| Field | Value |
|---|---|
| **Option** | `Separator between organization alias and group` |
| **Property Key** | `kommons.orgs.prefix.separator` |
| **Type** | String |
| **Default** | `_` |

The default underscore is what this mapper has always used, but it is a poor delimiter whenever aliases or group
names may contain one themselves:

```
acme_dev_leads
```

A consumer splitting that value cannot tell whether the organization is `acme` and the group `dev_leads`, or the
organization is `acme_dev` and the group `leads`. If anything downstream parses the value back apart, pick a
separator that cannot occur in either part:

| Separator | Result |
|---|---|
| `_` (default) | `acme_engineering/leads` |
| `:` | `acme:engineering/leads` |
| `::` | `acme::engineering/leads` |
| `/` | `acme/engineering/leads` |

An empty value falls back to `_`, because the Admin Console cannot distinguish "unset" from "deliberately empty"
and silently producing `acmeleads` would be worse than doing nothing.

The separator only applies when prefixing is enabled, and only once at the front of the label — it never replaces
the `/` inside a `path` label.

---

### 📝 Notes

- The separator defaults to an underscore `_`: `orgalias_groupname`
- The prefix only applies to group names listed in the token — it does **not** affect group names or structures inside Keycloak
- Token consumers must be prepared to handle the prefixed format if this is enabled

---

## 🧾 Emit Flattened Group Claim

By default, this mapper writes organization-scoped group memberships into a structured JSON claim under `organization`. However, you can configure it to emit a **flat list of group names** at the top level using the `groups` claim.

### 🔧 Setting

| Field                                     | Value                                |
|-------------------------------------------|--------------------------------------|
| **Option**                                | `Emit flattened group claim`         |
| **Property Key**                          | `kommons.emit.flattened.group.claim` |
| **Type**                                  | Boolean                              |
| **Default**                               | `false`                              |

---

### 🧭 Purpose

Enable this if your downstream systems or APIs expect a standard flat `groups` claim, rather than nested JSON under `organization`. This is especially useful when aggregating roles or permissions in services that don’t support structured claims.

If enabled, and used with **prefixing**, group names are emitted in a namespaced format like `acme_developers`.

---

### 🧪 Example Output

#### 🔹 Default Structure (Nested)

```json
{
  "organization": {
    "acme": {
      "groups": ["developers", "admins"]
    }
  }
}
```

#### 🔸 With `flat.group.claim = true`

```json
{
  "groups": ["developers", "admins"]
}
```

#### 🔸 With `flat.group.claim = true` and `prefix.groups.with.organization = true`

```json
{
  "groups": ["acme_developers", "acme_admins"]
}
```

---

### ✅ When to Use

Enable this if:

- You need a **standard top-level `groups` claim**
- You’re integrating with systems that expect groups in flat string format (e.g., APIs, IAM middleware)
- You want to **disambiguate duplicate group names** across tenants — use with prefixing

---

### 🔗 Related Settings

- `prefix.groups.with.organization`: Enables name prefixing (`org_groupname`), most useful when flattened

---


## 🧮 The full matrix

`label`, `prefix` and `flatten` compose freely. For a user in organization `acme` who is a member of the top level
group `sales` and the nested group `engineering/leads`:

| label | prefix | flatten | Claim |
|---|---|---|---|
| `name` | off | off | `"organization": { "acme": { "groups": ["sales", "leads"] } }` |
| `name` | off | on | `"groups": ["sales", "leads"]` |
| `name` | on | off | `"organization": { "acme": { "groups": ["acme_sales", "acme_leads"] } }` |
| `name` | on | on | `"groups": ["acme_sales", "acme_leads"]` |
| `path` | off | off | `"organization": { "acme": { "groups": ["sales", "engineering/leads"] } }` |
| `path` | off | on | `"groups": ["sales", "engineering/leads"]` |
| `path` | on | off | `"organization": { "acme": { "groups": ["acme_sales", "acme_engineering/leads"] } }` |
| `path` | on | on | `"groups": ["acme_sales", "acme_engineering/leads"]` |

The prefix is applied **once, at the front of the whole label** — `acme_engineering/leads`, not
`acme_engineering/acme_leads`.

Prefixing matters most when flattening: without it, two organizations that both have a `leads` group become
indistinguishable once merged into one flat array. With `path` labels the risk is smaller but not gone, since
`engineering/leads` can exist in several organizations too.

---

## 🤝 Using it with Single Organization per Session

This mapper pairs naturally with [Single Organization per Session]({{ site.baseurl }}/single_organization_per_session.html),
and the combination is the one most deployments actually want.

**Why they fit together.** This mapper emits groups for *every organization it resolves*. If a client requests
`organization:*`, that is every organization the user belongs to, and a flattened claim then mixes groups from all
of them into one array. Single Organization per Session guarantees the request resolves to **exactly one**
organization, which makes the group claim unambiguous.

**What that buys you:**

- With the enforcer rejecting `organization:*` and multiple organization scopes, `groups` can only ever describe one
  organization.
- With **flatten on**, you get a plain top-level `groups` array — the shape most applications and API gateways
  expect — and it is safe, because only one organization can contribute to it.
- **Prefixing becomes optional** rather than a necessity. It is still worth enabling if your downstream systems
  compare group names across sessions and you want the tenant visible in the value itself.

**Recommended combination for a single-tenant-per-session setup:**

| Setting | Value |
|---|---|
| Single Organization per Session | enabled (enforcer, plus injector for OIDC clients you cannot change) |
| `kommons.emit.flattened.group.claim` | `true` |
| `kommons.prefix.groups.with.organization` | `false`, unless you want the tenant in every value |
| `kommons.orgs.group.label` | `path` if your groups are nested, otherwise `name` |
| `kommons.orgs.group.source` | `auto` |

**One shared mechanism worth knowing.** Both features read the same `kc.org` client session note. When the user
selects an organization — or when a session is bound to one — this mapper resolves that organization directly from
the note rather than from the requested scope. So the organization the user picked at login is exactly the one whose
groups end up in the token, including on single sign-on where no selection screen was shown.

---

## 🧩 Scope Resolution Logic

The **Organization-aware Group Mapper** needs to know which organization the current authentication context refers to, so it can include only the relevant group data.

It resolves the organization context using the following logic:

### 1. 🔖 Session Attribute (Preferred)

If the login session includes the `ORGANIZATION_ATTRIBUTE` (typically set by Keycloak internally), this takes priority. The value should be the **ID of the organization** the user is authenticating into.

This attribute is usually populated during the login flow when a user selects an organization.

### 2. 🔍 OAuth Scope Resolution (Fallback)

If no session attribute is found, the mapper attempts to extract the organization from the **requested scopes** using `OrganizationScope.valueOfScope(...)`.

For example, if the token request includes a scope like:

```
scope=openid organization:globex
```

Then the mapper will attempt to resolve `"globex"` to an `OrganizationModel` and limit group inclusion accordingly.

---

### ⚠️ No Match? Then No Groups

If neither the session nor the scopes yield a resolvable organization:

- The mapper exits without modifying the token.
- No group claims are added.

This ensures that group data is only ever exposed in a known and intentional organization context.

---

### 🛡️ Why This Matters

This resolution logic is essential for **tenant isolation**:

- It prevents leaking group data across organizational boundaries
- It allows token claims to reflect the user's group within the correct tenant
- It supports both interactive (session-based) and client-credential (scope-based) login flows
