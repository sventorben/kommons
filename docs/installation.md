---
layout: default
title: Installation
nav_order: 2
---

# 🚀 Installation Instructions

This guide explains how to install and configure the **Kommons** extensions in your Keycloak server.

No need to clone or build anything — each release includes a prebuilt, ready-to-use JAR.

---

## 📥 1. Download the JAR

Visit the [Releases](https://github.com/sventorben/kommons/releases) page and download the JAR that matches your Keycloak version.

> 📌 Example: If you use **Keycloak `26.2.5`**, download **`kommons-26.x.x.jar`**.

---

## 📁 2. Deploy the Extension

Copy the downloaded JAR into your Keycloak installation:

```bash
cp kommons-26.x.x.jar $KEYCLOAK_HOME/providers/
```

Restart your Keycloak server:

```bash
$KEYCLOAK_HOME/bin/kc.sh restart
```

> ✅ This works with modern, Quarkus-based Keycloak distributions (version 17 and up).

---

---

## 🐳 Container Image (Docker)

For Docker-based setups, mount or copy the JAR to:

```bash
/opt/keycloak/providers
```

You may want to check out this example:
👉 [docker-compose.yml](https://github.com/sventorben/kommons/blob/main/docker-compose.yml)

---

## 🧩 Version Compatibility

This extension uses a simple versioning scheme aligned with Keycloak:

> If you're using **Keycloak version `X.y.z`**, use **extension version `X.b.c`**.

### 🔢 Example

- **Keycloak 24.0.1** → Use **extension version `24.x.x`**
- **Keycloak 22.0.5** → Use **extension version `22.x.x`**

Each `X.b.c` release of the extension is compiled and tested against Keycloak `X.y.z`.

---

## ✅ What You Can Expect

- Keycloak’s SPIs are generally stable, so minor version mismatches (e.g., `24.0.0` vs `24.0.2`) are usually fine.
- Check the [compatibility test matrix](https://github.com/sventorben/kommons/actions/workflows/matrix.yml) for current tested versions.
- Or simply try it — it often works across patch versions.

---

## ⚠️ Maintenance Notes

- **No backports**: Only the latest extension version for each Keycloak major version is supported.
- If you're using an older Keycloak version:
    - ✅ Recommended: **Upgrade your Keycloak instance**
    - 🛠️ Alternative: **Fork the repository** and build your own version

---

## 📦 Red Hat SSO Compatibility

If you're using **Red Hat Single Sign-On (RH-SSO)**, check the equivalent Keycloak version using this mapping:
👉 [Red Hat to Keycloak version mapping](https://access.redhat.com/articles/2342881)

Use the corresponding extension version following the same rules above.

---

## ❓ Need Help?

- Confirm that Keycloak loaded the extension (check logs)
- Validate your configuration settings in the Keycloak Admin Console
- Double-check you're using the correct versioned JAR for your Keycloak version
- Still stuck? [Open an issue](https://github.com/sventorben/kommons/issues)

---
