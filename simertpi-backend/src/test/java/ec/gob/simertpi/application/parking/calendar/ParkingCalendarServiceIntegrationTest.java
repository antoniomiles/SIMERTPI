package ec.gob.simertpi.application.parking.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ParkingCalendarServiceIntegrationTest {

    private static final UUID ZONA_TEST_01 =
            UUID.fromString("6b4bda6a-5f36-4913-890e-417a7b39f40a");

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