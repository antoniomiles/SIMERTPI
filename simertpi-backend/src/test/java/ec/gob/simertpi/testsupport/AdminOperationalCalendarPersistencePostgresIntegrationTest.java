package ec.gob.simertpi.testsupport;

import ec.gob.simertpi.api.parking.admin.dto.CalendarExceptionWriteRequest;
import ec.gob.simertpi.api.parking.admin.dto.OperationalScheduleWriteRequest;
import ec.gob.simertpi.application.parking.AdminOperationalCalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
class AdminOperationalCalendarPersistencePostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private AdminOperationalCalendarService calendars;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void scheduleWritePersistsVersionAndAuditedValuesInPostgres() {
        UUID zoneId = zone();
        LocalDate effectiveFrom = LocalDate.now(java.time.ZoneId.of("America/Guayaquil")).plusDays(2);
        var created = calendars.createSchedule(new OperationalScheduleWriteRequest(zoneId, (short) 2,
                LocalTime.of(8, 0), LocalTime.of(17, 0), effectiveFrom, null, "Ordenanza de prueba", null));

        var stored = jdbc.queryForMap("""
                SELECT zone_id, day_of_week, start_time, end_time, valid_from, valid_to, row_version
                FROM parking.schedules WHERE id = ?
                """, created.getId());
        assertThat(stored.get("zone_id")).isEqualTo(zoneId);
        assertThat(((Number) stored.get("day_of_week")).intValue()).isEqualTo(2);
        assertThat(((java.sql.Time) stored.get("start_time")).toLocalTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(((java.sql.Time) stored.get("end_time")).toLocalTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(((java.sql.Date) stored.get("valid_from")).toLocalDate()).isEqualTo(effectiveFrom);
        assertThat(stored.get("valid_to")).isNull();
        assertThat(stored.get("row_version")).isEqualTo(0L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit.functional_audit_log
                WHERE action = 'OPERATIONAL_SCHEDULE_CREATED' AND resource_id = ?
                  AND metadata ->> 'reason' = 'Ordenanza de prueba'
                """, Integer.class, created.getId())).isEqualTo(1);
    }

    @Test
    void futureCalendarExceptionPersistsExplicitScopeAndChargeRuleWithAudit() {
        UUID zoneId = zone();
        LocalDate effectiveFrom = LocalDate.now(java.time.ZoneId.of("America/Guayaquil")).plusDays(2);
        LocalDate holidayDate = effectiveFrom.plusDays(2);
        var created = calendars.createHoliday(new CalendarExceptionWriteRequest(holidayDate,
                "Excepción sintética QA", zoneId, "SPECIAL_SCHEDULE", true, LocalTime.of(9, 0),
                LocalTime.of(13, 0), "Referencia de prueba", effectiveFrom, null,
                "Fixture de integración", null));

        var stored = jdbc.queryForMap("""
                SELECT zone_id, holiday_date, name, holiday_type, tariffed, start_time, end_time,
                       normative_reference, valid_from, valid_to, active, updated_at
                FROM parking.holidays WHERE id = ?
                """, created.getId());
        assertThat(stored.get("zone_id")).isEqualTo(zoneId);
        assertThat(((java.sql.Date) stored.get("holiday_date")).toLocalDate()).isEqualTo(holidayDate);
        assertThat(stored.get("name")).isEqualTo("Excepción sintética QA");
        assertThat(stored.get("holiday_type")).isEqualTo("SPECIAL_SCHEDULE");
        assertThat(stored.get("tariffed")).isEqualTo(true);
        assertThat(((java.sql.Time) stored.get("start_time")).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(((java.sql.Time) stored.get("end_time")).toLocalTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(stored.get("normative_reference")).isEqualTo("Referencia de prueba");
        assertThat(((java.sql.Date) stored.get("valid_from")).toLocalDate()).isEqualTo(effectiveFrom);
        assertThat(stored.get("valid_to")).isNull();
        assertThat(stored.get("active")).isEqualTo(true);
        assertThat(stored.get("updated_at")).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit.functional_audit_log
                WHERE action = 'CALENDAR_EXCEPTION_CREATED' AND resource_id = ?
                  AND metadata ->> 'reason' = 'Fixture de integración'
                """, Integer.class, created.getId())).isEqualTo(1);
    }

    private UUID zone() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking.zones(id, code, name, active, created_at, updated_at)
                VALUES (?, ?, 'QA integration fixture', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, "QA-CAL-" + id);
        return id;
    }
}
