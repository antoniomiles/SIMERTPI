package ec.gob.simertpi.application.parking.calendar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ParkingCalendarServiceIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {

    private final UUID ZONA_TEST_01 = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createCalendarFixture() {
        jdbc.update("INSERT INTO parking.zones(id, code, name) VALUES (?, ?, ?)",
                ZONA_TEST_01, "CAL-" + ZONA_TEST_01, "Calendar test fixture");
        // Fixture values only: no production normative defaults are introduced.
        for (short day = 1; day <= 7; day++) {
            jdbc.update("INSERT INTO parking.schedules(id, zone_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), ZONA_TEST_01, day, LocalTime.of(8, 0),
                    LocalTime.of(day < 6 ? 18 : 13, 0));
        }
    }

    @AfterEach
    void removeCalendarFixture() {
        jdbc.update("DELETE FROM parking.schedules WHERE zone_id = ?", ZONA_TEST_01);
        jdbc.update("DELETE FROM parking.zones WHERE id = ?", ZONA_TEST_01);
    }

    @Autowired
    private ParkingCalendarService service;

    @Test
    void shouldBeOperationalOnFridayAtTen() {

        assertTrue(
                service.isOperational(
                        ZONA_TEST_01,
                        LocalDate.of(2026, 10, 2),
                        LocalTime.of(10, 0)
                )
        );
    }

    @Test
    void shouldNotBeOperationalOnFridayAtNineteen() {

        assertFalse(
                service.isOperational(
                        ZONA_TEST_01,
                        LocalDate.of(2026, 10, 2),
                        LocalTime.of(19, 0)
                )
        );
    }

    @Test
    void shouldBeOperationalOnSaturdayAtTwelve() {

        assertTrue(
                service.isOperational(
                        ZONA_TEST_01,
                        LocalDate.of(2026, 10, 3),
                        LocalTime.of(12, 0)
                )
        );
    }

    @Test
    void shouldNotBeOperationalOnSaturdayAtFourteen() {

        assertFalse(
                service.isOperational(
                        ZONA_TEST_01,
                        LocalDate.of(2026, 10, 3),
                        LocalTime.of(14, 0)
                )
        );
    }

    @Test
    void shouldBeOperationalOnSundayAtTen() {

        assertTrue(
                service.isOperational(
                        ZONA_TEST_01,
                        LocalDate.of(2026, 10, 4),
                        LocalTime.of(10, 0)
                )
        );
    }

    @Test
    void shouldNotBeOperationalOnSundayAtFourteen() {

        assertFalse(
                service.isOperational(
                        ZONA_TEST_01,
                        LocalDate.of(2026, 10, 4),
                        LocalTime.of(14, 0)
                )
        );
    }
}
