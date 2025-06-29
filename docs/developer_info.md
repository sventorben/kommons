---
layout: default
title: Notice for Developers
nav_order: 5
---

# Notice for Developers
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Not a Developer Library

** *Kommons* is intentionally _not_ designed to be a developer-facing library or framework.** It is a collection of ready-to-use, production-quality Keycloak extensions packaged as JARs, meant to be deployed as-is.

These components are provided for operational use in Keycloak servers—not for extension, subclassing, or programmatic integration by other developers.

---

## Design Philosophy

*Kommons* follows these principles:

- ✅ **Use it, don’t build on it**: The extensions are meant to be _used_, not consumed as APIs or SPIs.
- ❌ **No extension support**: No part of this project is designed for you to subclass or extend.
- 🚫 **No Public API Surface**: All classes and methods should be treated as internal unless explicitly documented otherwise (which none are).
- 🛠 **Plug-and-play**: Build it, drop it into Keycloak, and configure via the Admin Console.

---

## No Support for API Consumers

This project:

- Does **not** expose or maintain stable Java APIs.
- May **change internal implementation details** at any time—even between patch versions.
- Provides **no guarantees** around method signatures, class structure, or internal contracts.

If you're looking for something to build on or extend via SPI, this is **not** the project for you.

---

## Recommended Usage

1. **Download or build the JAR**
2. **Deploy it to your Keycloak server** (`$KEYCLOAK_HOME/providers`)
3. **Restart and configure it via the Keycloak Admin Console**
4. **Use the functionality through Keycloak’s UI and token outputs — not through code**

## Conclusion

*Kommon*s is built to solve specific, real-world problems in a robust and focused way. If you need a flexible SPI for building your own Keycloak tools, you may want to look into alternative projects or build something custom.

> TL;DR: **Use the JARs. Don't code against them.**
