---
layout: default
title: Multi Client Attributes Claims Mapper
nav_order: 5
---

# 🧩 Multi Client Attributes Claims Mapper

The **Multi Client Attributes Claims Mapper** is a Keycloak protocol mapper that promotes **client attributes** directly into OIDC token claims. It lets you declaratively bind any number of client attributes to custom claim names using a single key/value map, without writing custom code per attribute.

---

## 🛠️ What It Does

When a token is issued, this mapper:

- Reads a map from its configuration: each **claim name** paired with the **client attribute** it takes its value from
- For each pair, looks up the attribute value on the **requesting client**
- Infers the appropriate JSON type from the attribute value (boolean, int, long, JSON, or String)
- Adds each resolved attribute as a claim in the token under the configured claim name

Attributes missing from the client are silently skipped — no error is raised and the remaining pairs are still processed.

---

## 🎯 Use Cases

- ✅ Injecting client-specific metadata (e.g. tenant ID, tier, feature flags) into tokens without a custom mapper per attribute
- ✅ Driving authorization decisions downstream based on client-level configuration
- ✅ Decoupling per-client claim values from the mapper implementation — change attribute values in the admin UI, not code
- ❌ Not intended for user attributes — use Keycloak's built-in user attribute mappers for those

---

## ⚙️ Configuration in Admin Console

1. Go to your **Client** or **Client Scope** in the Keycloak Admin Console
2. Navigate to **Protocol Mappers**
3. Click **Add mapper → By configuration**
4. Select **Multi Client Attributes Claims Mapper**
5. Fill out the form:

   | Field                    | Value                                            |
   |--------------------------|--------------------------------------------------|
   | **Name**                 | _(any descriptive name)_                         |
   | **Mapper Type**          | `Multi Client Attributes Claims Mapper`          |
   | **Add to ID token**      | ✅ / as needed                                   |
   | **Add to access token**  | ✅ / as needed                                   |
   | **Add to userinfo**      | ✅ / as needed                                   |
   | **Claims**               | One row per claim (see below)                    |

6. Click **Save**

---

## 🔧 Claims

| Field              | Value                                     |
|--------------------|-------------------------------------------|
| **Property key**   | `kommons.client.attr.claims`              |
| **Type**           | Map                                       |

A map of claim names to client attribute names. The **key** is the name of the claim added to the token, the
**value** is the name of the client attribute the claim takes its value from.

**Example rows:**

| Key (claim name)     | Value (client attribute name)   |
|----------------------|---------------------------------|
| `tenant_id`          | `my-app.tenant-id`              |
| `subscription_tier`  | `my-app.subscription-tier`      |
| `feature_flags`      | `my-app.feature-flags`          |

Rows are independent, so the order they appear in does not matter. A row with only one side filled in is ignored
when the token is issued, and saving the mapper reports it:

> ⚠️ Saving the mapper is rejected when a row has an empty claim name, an empty client attribute name, or when the
> same claim name is mapped to two different client attributes.

---

## 🔡 Claim Type Inference

The JSON type of each claim is inferred automatically from the attribute string value:

| Attribute value                    | Inferred type |
|------------------------------------|---------------|
| `true` or `false` (case-insensitive) | `boolean`   |
| Fits in a 32-bit integer           | `int`         |
| Fits in a 64-bit integer           | `long`        |
| Starts with `{…}` or `[…]`        | `JSON`        |
| Anything else                      | `String`      |

There is no explicit type configuration — the value on the client attribute drives the type.

---

## 🧪 Testing the Mapper

Use Keycloak's built-in **Token Evaluation** tool to inspect the output without a live login flow.

1. Go to the **Keycloak Admin Console**
2. Navigate to **Clients** → your client → **Client scopes** → **Evaluate**
3. Select a **User**
4. Inspect the generated **Access Token** or **ID Token**

Given a client with these attributes:

| Attribute name              | Attribute value |
|-----------------------------|-----------------|
| `my-app.tenant-id`          | `acme`          |
| `my-app.subscription-tier`  | `pro`           |
| `my-app.active`             | `true`          |

And mapper configuration:

| Key (claim name)     | Value (client attribute name)   |
|----------------------|---------------------------------|
| `tenant_id`          | `my-app.tenant-id`              |
| `subscription_tier`  | `my-app.subscription-tier`      |
| `active`             | `my-app.active`                 |

The resulting token will contain:

```json
{
  "tenant_id": "acme",
  "subscription_tier": "pro",
  "active": true
}
```

### ⚠️ If a claim is missing

Check the following:

- The client attribute name in the map exactly matches the attribute key on the client (case-sensitive)
- Both sides of the row are filled in
- The mapper is assigned to the correct client scope or client and the scope is requested

---
