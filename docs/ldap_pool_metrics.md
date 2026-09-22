---
layout: default
title: LDAP Connection Pool Metrics
nav_order: 7
---

# 🔌 LDAP Connection Pool Metrics

Exposes the state of the JVM-global JNDI connection pool that Keycloak's LDAP user federation uses, as
`keycloak_ldap_pool_*` gauges on Keycloak's `/metrics` endpoint.

Answers the questions the built-in metrics do not: *how many LDAP connections are open right now, how many are
idle, am I about to hit the pool limit?*

---

## ⚠️ Read this before enabling

{: .warning }
> **Keycloak has its own LDAP metrics, and for most deployments they are the better answer.**
> Since 26.x Keycloak ships a `keycloak_ldap_requests_*` timer covering request counts, latency and errors —
> enable it with `--features=ldap-metrics`. It costs no extra dependency and no JVM flags.
>
> This extension covers only what that timer does not: **pool occupancy**. If you do not specifically need to see
> idle/busy connections, use the built-in feature instead.

This provider reads JDK internals. That is a deliberate trade-off, spelled out under
[What this costs you](#-what-this-costs-you) — read it before putting this in production.

---

## 📊 Exposed metrics

| Metric | Meaning |
|---|---|
| `keycloak_ldap_pool_accessible` | `1` if the numbers could be read **and parsed**, `0` otherwise |
| `keycloak_ldap_pool_max_size` | Configured maximum connections per identity pool (`0` = unlimited) |
| `keycloak_ldap_pool_preferred_size` | Configured preferred number of connections |
| `keycloak_ldap_pool_init_size` | Configured initial number of connections |
| `keycloak_ldap_pool_timeout_milliseconds` | Idle timeout before a pooled connection is closed (`0` = none) |
| `keycloak_ldap_pool_identity_pools{authentication}` | Number of distinct host/port/principal groups |
| `keycloak_ldap_pool_connections{authentication,state}` | Connection counts, `state` in `total`/`idle`/`busy`/`expired` |

`authentication` is `none`, `simple` or `digest-md5` — the JDK keeps one pool per mechanism. A mechanism that is
not enabled never appears in the JDK's output and is reported as all zeros.

The four configuration gauges echo the JVM-wide `com.sun.jndi.ldap.connect.pool.*` system properties, **not**
per-provider settings. They are useful to confirm what the JVM actually picked up.

> 💡 **`keycloak_ldap_pool_accessible` is the gauge to alert on.** If it is `0`, every other value here is
> meaningless — either the JVM flag is missing or the JDK output could no longer be parsed. The provider
> deliberately reports `0` rather than inventing zeros.

---

## 🚀 Setup

### 1. JVM option

```
--add-exports java.naming/com.sun.jndi.ldap=ALL-UNNAMED
```

For example via `JAVA_OPTS_APPEND`. Without it `keycloak_ldap_pool_accessible` is `0` and nothing else is
populated.

`--add-exports` is deliberately the weaker option: the provider only invokes a *public* method, so the deep
reflection that `--add-opens` grants is not needed.

### 2. Enable the provider

It is opt-in, because of the JDK coupling:

```
--metrics-enabled=true
--spi-events-listener--kommons-ldap-pool-metrics--enabled=true
```

> ⚠️ Note the **double dashes** between SPI, provider and property. Keycloak 26 changed this format; the old
> single-dash spelling is not picked up.

The provider is registered as an event listener purely to get a startup hook — Keycloak initialises every enabled
provider factory at boot. You do **not** need to enable it as an event listener in any realm, and it never handles
an event.

### 3. Connection pooling must be on

Pool metrics only show something if Keycloak actually pools. Set **Connection pooling** on the LDAP user
federation provider (`connectionPooling = true`). Without it the JDK never creates a pool and all counters stay
at `0` while `accessible` remains `1`.

### 4. Scrape

```bash
curl -s http://<host>:9000/metrics | grep keycloak_ldap_pool
```

---

## 🧪 Try it out

The repository ships a ready-made playground:

```bash
mvn package -DskipTests
docker compose -p kommons-ldap -f docker-compose.ldap-metrics.yml up
```

It starts an OpenLDAP with two users and a Keycloak whose realm `ldap-metrics` already contains a matching LDAP
federation, on shifted ports so it can run next to the regular `docker-compose.yml`:

| | |
|---|---|
| Keycloak | <http://localhost:8081> (`admin` / `admin`), realm `ldap-metrics` |
| Metrics | <http://localhost:9001/metrics> |
| LDAP | `ldap://localhost:1389`, `cn=admin,dc=example,dc=org` / `adminpassword` |

Searching a federated user drives traffic through the pool:

```bash
# after an admin-cli token, search the federated realm
curl -s ".../admin/realms/ldap-metrics/users?search=alice" -H "Authorization: Bearer $TOKEN"

curl -s http://localhost:9001/metrics | grep keycloak_ldap_pool_connections
# keycloak_ldap_pool_connections{authentication="simple",state="idle"}  1.0
# keycloak_ldap_pool_connections{authentication="simple",state="total"} 1.0
```

Each search that cannot reuse a connection adds one, and connections return to the pool as `idle` when the
operation completes.

---

## 💸 What this costs you

Being explicit, because this is not an ordinary extension:

**It depends on a non-exported JDK package.** `com.sun.jndi.ldap.LdapPoolManager#showStats(PrintStream)` is
`public`, but its package is not exported and therefore outside any compatibility promise. That is the whole
reason the JVM flag is needed.

**It parses a debug dump, not an API.** The output is meant for humans. A future JDK can change it in a patch
release without deprecation and without a compile error. The provider defends against this as far as it can: if
not a single pool section is recognised, it reports `accessible = 0` instead of a plausible-looking wall of
zeros. Subtler changes — a renamed counter, a changed unit — would not be caught.

**It does not work in a native image.** Keycloak's GraalVM-based distribution cannot do this reflection.

**It reads under a lock.** `showStats` synchronises on the pool map while iterating, on a path that real LDAP
requests also take. A snapshot is memoized for two seconds so a scrape costs one pass regardless of how many
gauges are exposed, but the contention is not zero.

In exchange you get numbers that are otherwise not obtainable at all. Whether that trade is worth it depends on
whether pool occupancy is something you actually act on.

---

## 🩺 Troubleshooting

| Symptom | Cause |
|---|---|
| No `keycloak_ldap_pool_*` metrics at all | Provider not enabled, or `--metrics-enabled=true` missing. Check the startup log for `Registered LDAP connection pool metrics` |
| `keycloak_ldap_pool_accessible 0` | `--add-exports` missing, or the JDK output could not be parsed. The log names which |
| `accessible 1`, all counters `0` | Nothing has been pooled yet. Either no LDAP traffic, or **Connection pooling** is off on the federation provider |
| Counters stay `0` although users are found | Confirm the federation provider really is consulted. A provider whose `parentId` does not match the realm id is stored but never used |
| `NoSuchMethodError` on `strongReference` | Micrometer older than the one Keycloak ships. Verified against 1.16.3 in Keycloak 26.7 |
