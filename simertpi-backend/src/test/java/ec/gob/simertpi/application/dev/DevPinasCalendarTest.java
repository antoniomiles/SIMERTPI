package ec.gob.simertpi.application.dev;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
class DevPinasCalendarTest {
    @Test void goodFridayIsDeterministicAcrossYears() {
        assertThat(DevPinasCalendar.goodFriday(2026)).isEqualTo(LocalDate.of(2026,4,3));
        assertThat(DevPinasCalendar.goodFriday(2027)).isEqualTo(LocalDate.of(2027,3,26));
    }
}
