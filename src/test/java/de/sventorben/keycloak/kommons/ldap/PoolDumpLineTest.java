package de.sventorben.keycloak.kommons.ldap;

import org.junit.jupiter.api.Test;

import static de.sventorben.keycloak.kommons.ldap.PoolDumpLine.Counter.BUSY;
import static de.sventorben.keycloak.kommons.ldap.PoolDumpLine.Counter.EXPIRED;
import static de.sventorben.keycloak.kommons.ldap.PoolDumpLine.Counter.IDLE;
import static de.sventorben.keycloak.kommons.ldap.PoolDumpLine.Counter.SIZE;
import static de.sventorben.keycloak.kommons.ldap.PoolDumpLine.Field.IDLE_TIMEOUT;
import static de.sventorben.keycloak.kommons.ldap.PoolDumpLine.Field.MAX_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for reading the two numeric shapes of line in the pool dump. */
class PoolDumpLineTest {

    @Test
    void extractsEachCounterFromAConnectionIdentityLine() {
        PoolDumpLine line = PoolDumpLine.of(
            "host:389:::null:cn=admin:size=10; use=45; busy=2; idle=7; expired=1");

        assertThat(line.holdsIdentityCounters()).isTrue();
        assertThat(line.countOf(SIZE)).isEqualTo(10);
        assertThat(line.countOf(BUSY)).isEqualTo(2);
        assertThat(line.countOf(IDLE)).isEqualTo(7);
        assertThat(line.countOf(EXPIRED)).isEqualTo(1);
    }

    @Test
    void aCounterThatIsAbsentOrMalformedCountsAsZero() {
        assertThat(PoolDumpLine.of("size=10").countOf(BUSY)).isZero();
        assertThat(PoolDumpLine.of("size=; idle=3").countOf(SIZE)).isZero();
    }

    @Test
    void readsALabelledFieldOnlyFromTheLineThatCarriesIt() {
        assertThat(PoolDumpLine.of("idle timeout: 300000").valueOf(IDLE_TIMEOUT)).hasValue(300000L);
        assertThat(PoolDumpLine.of("idle timeout: 300000").valueOf(MAX_SIZE)).isEmpty();
    }

    @Test
    void aLabelWithANonNumericValueIsReportedAsAbsent() {
        // The caller keeps whatever it had rather than resetting it to zero.
        assertThat(PoolDumpLine.of("idle timeout: soon").valueOf(IDLE_TIMEOUT)).isEmpty();
    }

    @Test
    void recognisesTheSectionHeaderOfEachMechanism() {
        assertThat(PoolDumpLine.of("anonymous pools:").opensSectionFor()).contains(AuthMechanism.NONE);
        assertThat(PoolDumpLine.of("simple auth pools:").opensSectionFor()).contains(AuthMechanism.SIMPLE);
        assertThat(PoolDumpLine.of("DIGEST-MD5 auth pools:").opensSectionFor())
            .contains(AuthMechanism.DIGEST_MD5);
    }

    @Test
    void aLineThatDoesNotEndInPoolsOpensNoSection() {
        assertThat(PoolDumpLine.of("anonymous connection groups:").opensSectionFor()).isEmpty();
        assertThat(PoolDumpLine.of("current pool size: 1").opensSectionFor()).isEmpty();
    }
}
