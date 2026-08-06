package de.sventorben.keycloak.kommons.auth;

import org.junit.jupiter.api.Test;
import org.keycloak.models.UserModel;
import org.mockito.ArgumentCaptor;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for reading and recording the usual login times of a user.
 */
class UnusualLoginTimeUserWrapperTest {

    private static final String ATTRIBUTE = "kommons.usualLoginTimes";

    private final UserModel user = mock(UserModel.class);

    @Test
    void readsTheEarliestAndLatestRecordedTime() {
        recorded("09:15", "07:30", "17:45");

        UnusualLoginTimeUserWrapper wrapper = new UnusualLoginTimeUserWrapper(user);

        assertThat(wrapper.getMinTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(wrapper.getMaxTime()).isEqualTo(LocalTime.of(17, 45));
    }

    @Test
    void aSingleRecordedTimeIsBothMinimumAndMaximum() {
        recorded("12:00");

        UnusualLoginTimeUserWrapper wrapper = new UnusualLoginTimeUserWrapper(user);

        assertThat(wrapper.getMinTime()).isEqualTo(LocalTime.NOON);
        assertThat(wrapper.getMaxTime()).isEqualTo(LocalTime.NOON);
    }

    @Test
    void withoutHistoryTheRangeSpansTheWholeDay() {
        recorded();

        UnusualLoginTimeUserWrapper wrapper = new UnusualLoginTimeUserWrapper(user);

        assertThat(wrapper.hasRecordedLoginTimes()).isFalse();
        assertThat(wrapper.getMinTime()).isEqualTo(LocalTime.MIN);
        assertThat(wrapper.getMaxTime()).isEqualTo(LocalTime.MAX);
    }

    @Test
    void historyIsReportedAsSoonAsThereIsOneEntry() {
        recorded("08:00");

        assertThat(new UnusualLoginTimeUserWrapper(user).hasRecordedLoginTimes()).isTrue();
    }

    @Test
    void aNewLoginTimeIsPrepended() {
        // Stored via ISO_LOCAL_TIME, which unlike LocalTime.toString() always writes the seconds.
        recorded("08:00", "09:00");

        new UnusualLoginTimeUserWrapper(user).addSuccessfulLoginTime(LocalTime.of(10, 30));

        assertThat(storedTimes()).startsWith("10:30:00").hasSize(3);
    }

    @Test
    void onlyTheFiveMostRecentLoginTimesAreKept() {
        recorded("01:00", "02:00", "03:00", "04:00", "05:00");

        new UnusualLoginTimeUserWrapper(user).addSuccessfulLoginTime(LocalTime.of(6, 0));

        assertThat(storedTimes())
            .hasSize(5)
            .startsWith("06:00:00")
            .doesNotContain("05:00");
    }

    @Test
    void theFirstLoginTimeStartsTheHistory() {
        recorded();

        new UnusualLoginTimeUserWrapper(user).addSuccessfulLoginTime(LocalTime.of(6, 0));

        assertThat(storedTimes()).containsExactly("06:00:00");
    }

    private void recorded(String... times) {
        when(user.getAttributeStream(ATTRIBUTE)).thenAnswer(invocation -> Stream.of(times));
    }

    @SuppressWarnings("unchecked")
    private List<String> storedTimes() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(user).setAttribute(eq(ATTRIBUTE), captor.capture());
        return captor.getValue();
    }
}
