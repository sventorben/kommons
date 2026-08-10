package de.sventorben.keycloak.kommons.ldap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for parsing {@code com.sun.jndi.ldap.LdapPoolManager#showStats(PrintStream)}.
 *
 * <p>The fixtures below are verbatim output of a real JDK 21 run against an OpenLDAP server, so the
 * parser is pinned to the actual format rather than to an assumption about it. The reflective call
 * itself is exercised end-to-end by {@link LdapPoolMetricsIT} inside a running Keycloak.
 */
class LdapConnectionPoolIntrospectorTest {

    /** Two pooled connections against one directory, both in use, digest pool not enabled. */
    private static final String POPULATED = """
        ***** start *****
        idle timeout: 300000
        maximum pool size: 20
        preferred pool size: 5
        initial pool size: 1
        protocol types: plain\s
        authentication types: none simple\s
        anonymous pools:
        ===== Pool start ======================
        maximum pool size: 20
        preferred pool size: 5
        initial pool size: 1
        current pool size: 0
        ====== Pool end =====================
        simple auth pools:
        ===== Pool start ======================
        maximum pool size: 20
        preferred pool size: 5
        initial pool size: 1
        current pool size: 1
           localhost:1389:::null:cn=admin,dc=example,dc=org:size=2; use=2; busy=2; idle=0; expired=0
        ====== Pool end =====================
        ***** end *****
        """;

    /** Nothing has connected yet: pools exist but hold no connections. */
    private static final String EMPTY = """
        ***** start *****
        idle timeout: 0
        maximum pool size: 0
        preferred pool size: 0
        initial pool size: 1
        protocol types: plain\s
        authentication types: none simple\s
        anonymous pools:
        ===== Pool start ======================
        current pool size: 0
        ====== Pool end =====================
        simple auth pools:
        ===== Pool start ======================
        current pool size: 0
        ====== Pool end =====================
        ***** end *****
        """;

    // --- configuration ------------------------------------------------------------------------------------------

    @Test
    void readsTheConfiguredSizesFromTheHeader() {
        LdapPoolSnapshot snapshot = LdapConnectionPoolIntrospector.parse(POPULATED);

        assertThat(snapshot.accessible()).isTrue();
        assertThat(snapshot)
            .extracting(LdapPoolSnapshot::idleTimeoutMillis, LdapPoolSnapshot::maxSize,
                LdapPoolSnapshot::prefSize, LdapPoolSnapshot::initSize)
            .containsExactly(300000L, 20, 5, 1);
    }

    @Test
    void theSizesRepeatedInsideEachPoolSectionDoNotOverwriteTheHeader() {
        // Every section repeats "maximum pool size:" etc. Only the leading ones are the configuration.
        String misleading = POPULATED.replace("""
            maximum pool size: 20
            preferred pool size: 5
            initial pool size: 1
            current pool size: 1""", """
            maximum pool size: 999
            preferred pool size: 888
            initial pool size: 777
            current pool size: 1""");

        LdapPoolSnapshot snapshot = LdapConnectionPoolIntrospector.parse(misleading);

        assertThat(snapshot.maxSize()).isEqualTo(20);
        assertThat(snapshot.prefSize()).isEqualTo(5);
        assertThat(snapshot.initSize()).isEqualTo(1);
    }

    // --- per authentication mechanism ---------------------------------------------------------------------------

    @Test
    void countsTheConnectionsOfTheSimplePool() {
        AuthPoolStats simple = LdapConnectionPoolIntrospector.parse(POPULATED).forAuth("simple");

        // identityPools, total, idle, busy, expired
        assertThat(simple).isEqualTo(new AuthPoolStats(1, 2, 0, 2, 0));
    }

    @Test
    void anEmptySectionYieldsZeros() {
        AuthPoolStats anonymous = LdapConnectionPoolIntrospector.parse(POPULATED).forAuth("none");

        assertThat(anonymous.identityPools()).isZero();
        assertThat(anonymous.total()).isZero();
    }

    @Test
    void aMechanismWithoutASectionIsReportedAsEmpty() {
        // The digest pool is not created unless it is enabled, so it never shows up in the dump.
        assertThat(LdapConnectionPoolIntrospector.parse(POPULATED).forAuth("digest-md5"))
            .isEqualTo(AuthPoolStats.empty());
    }

    @Test
    void severalIdentityPoolsAreSummedUp() {
        String twoDirectories = POPULATED.replace(
            "   localhost:1389:::null:cn=admin,dc=example,dc=org:size=2; use=2; busy=2; idle=0; expired=0",
            """
               localhost:1389:::null:cn=admin,dc=example,dc=org:size=2; use=2; busy=2; idle=0; expired=0
               other:389:::null:cn=svc,dc=example,dc=org:size=3; use=9; busy=1; idle=2; expired=1""")
            .replace("current pool size: 1", "current pool size: 2");

        AuthPoolStats simple = LdapConnectionPoolIntrospector.parse(twoDirectories).forAuth("simple");

        // identityPools, total, idle, busy, expired
        assertThat(simple).isEqualTo(new AuthPoolStats(2, 5, 2, 3, 1));
    }

    @Test
    void anIdlePoolIsReportedAsIdleRatherThanBusy() {
        String idled = POPULATED.replace(
            "size=2; use=2; busy=2; idle=0; expired=0",
            "size=2; use=7; busy=0; idle=2; expired=0");

        AuthPoolStats simple = LdapConnectionPoolIntrospector.parse(idled).forAuth("simple");

        assertThat(simple.busy()).isZero();
        assertThat(simple.idle()).isEqualTo(2);
        assertThat(simple.total()).isEqualTo(2);
    }

    @Test
    void aPoolNobodyHasUsedYetIsAllZeros() {
        LdapPoolSnapshot snapshot = LdapConnectionPoolIntrospector.parse(EMPTY);

        assertThat(snapshot.accessible()).isTrue();
        assertThat(snapshot.forAuth("none")).isEqualTo(AuthPoolStats.empty());
        assertThat(snapshot.forAuth("simple")).isEqualTo(AuthPoolStats.empty());
    }

    // --- robustness ---------------------------------------------------------------------------------------------

    @Test
    void anUnrecognisableDumpIsReportedAsUnavailable() {
        // Reporting zeros here would be inventing numbers. accessible=0 is the honest answer, and it
        // is what operators alert on.
        LdapPoolSnapshot snapshot = LdapConnectionPoolIntrospector.parse("something else entirely");

        assertThat(snapshot.accessible()).isFalse();
        assertThat(snapshot.maxSize()).isZero();
        assertThat(snapshot.forAuth("simple")).isEqualTo(AuthPoolStats.empty());
    }

    @Test
    void aRenamedSectionHeaderIsNoticedRatherThanSilentlyIgnored() {
        // If a future JDK renames the section headers, every count would parse to zero. That must
        // surface as accessible=0 instead of looking like an idle pool.
        String renamed = POPULATED
            .replace("anonymous pools:", "anonymous connection groups:")
            .replace("simple auth pools:", "simple auth connection groups:");

        assertThat(LdapConnectionPoolIntrospector.parse(renamed).accessible()).isFalse();
    }

    @Test
    void aNonNumericValueFallsBackInsteadOfThrowing() {
        String garbled = POPULATED.replace("idle timeout: 300000", "idle timeout: soon");

        assertThat(LdapConnectionPoolIntrospector.parse(garbled).idleTimeoutMillis()).isZero();
    }

    // --- the per-identity value extractor -----------------------------------------------------------------------

    @Test
    void extractsEachCountFromAStatsLine() {
        String line = "host:389:::null:cn=admin:size=10; use=45; busy=2; idle=7; expired=1";

        assertThat(LdapConnectionPoolIntrospector.extract(line, "size=")).isEqualTo(10);
        assertThat(LdapConnectionPoolIntrospector.extract(line, "busy=")).isEqualTo(2);
        assertThat(LdapConnectionPoolIntrospector.extract(line, "idle=")).isEqualTo(7);
        assertThat(LdapConnectionPoolIntrospector.extract(line, "expired=")).isEqualTo(1);
    }

    @Test
    void extractReturnsZeroForAMissingOrMalformedKey() {
        assertThat(LdapConnectionPoolIntrospector.extract("size=10", "missing=")).isZero();
        assertThat(LdapConnectionPoolIntrospector.extract("size=; idle=3", "size=")).isZero();
    }
}
