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
2. A group hierarchy like this:

```
organizations/
├── acme/
│   ├── developers
│   └── admins
├── globex/
│   ├── users
```

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

   | Field                    | Value                                         |
      |--------------------------|-----------------------------------------------|
   | **Name**                | `Organization-aware Group Mapper`                            |
   | **Mapper Type**         | `Organization-scoped Group Mapper`            |
   | **Token Claim Name**    | `organization`                                |
   | **Add to ID token**     | ✅                                             |
   | **Add to access token** | ✅                                             |
   | **Add to userinfo**     | ✅                                             |

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

### 📝 Notes

- The underscore `_` is used as the delimiter: `orgalias_groupname`
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
