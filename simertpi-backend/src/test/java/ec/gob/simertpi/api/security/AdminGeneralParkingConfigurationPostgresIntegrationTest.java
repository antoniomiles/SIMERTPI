package ec.gob.simertpi.api.security;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.application.parking.BillingDayCalendarService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminGeneralParkingConfigurationPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-general-config-password";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired BillingDayCalendarService billingDays;
    @Autowired ObjectMapper objectMapper;

    private UUID adminId;
    private UUID testZoneId;
    private final String reference = "ORD-QA-" + UUID.randomUUID();
    private Cookie session;
    private Cookie csrf;

    @BeforeEach
    void authenticateAdmin() throws Exception {
        String username = "general-config-admin-" + UUID.randomUUID();
        adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                adminId, username, username + "@example.invalid", encoder.encode(PASSWORD), "Config", "Admin");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'", adminId);
        MvcResult csrfResult = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isNoContent()).andReturn();
        csrf = cookie(csrfResult, "__Host-SIMERTPI_ADMIN_XSRF");
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        session = cookie(login, AdminAuthenticationService.COOKIE);
    }

    @AfterEach
    void cleanTestConfiguration() {
        jdbc.update("DELETE FROM parking.tariffs WHERE normative_reference=?", reference);
        jdbc.update("DELETE FROM parking.billing_day_calendars WHERE normative_reference=?", reference);
        if (testZoneId != null) jdbc.update("DELETE FROM parking.zones WHERE id=?", testZoneId);
    }

    @Test
    void newReadsAreAdminOnlyNoStoreAndWritesRequireCsrf() throws Exception {
        mvc.perform(get("/api/v1/admin/parking/billing-day-calendars").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/parking/configuration/effective?at=2026-10-20T12:00:00Z").secure(true))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/parking/billing-day-calendars").secure(true).cookie(session))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post("/api/v1/admin/parking/billing-day-calendars").secure(true).cookie(session)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void zonalCalendarReplacesTheWholeGeneralWeekAndIsAuditedWithoutCopyingRulesPerZone() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("America/Guayaquil"));
        LocalDate monday = today.plusDays(1);
        while (monday.getDayOfWeek() != DayOfWeek.MONDAY) monday = monday.plusDays(1);
        LocalDate sunday = monday.plusDays(6);
        UUID zoneId = UUID.randomUUID();
        testZoneId = zoneId;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", zoneId, "QA-BD-" + suffix, "Billing days QA");

        postCalendar(null, monday, null, "[1]").andExpect(status().isCreated());
        MvcResult zoneCalendar = postCalendar(zoneId, monday, sunday, "[2]").andExpect(status().isCreated()).andReturn();
        UUID calendarId = UUID.fromString(objectMapper.readTree(zoneCalendar.getResponse().getContentAsString()).asText());
        postCalendar(zoneId, monday, sunday, "[1]").andExpect(status().isConflict());

        var mondayGeneral = billingDays.resolve(UUID.randomUUID(), monday);
        var mondayZone = billingDays.resolve(zoneId, monday);
        var tuesdayZone = billingDays.resolve(zoneId, monday.plusDays(1));
        assertThat(mondayGeneral.configured()).isTrue();
        assertThat(mondayGeneral.chargeable()).isTrue();
        assertThat(mondayZone.chargeable()).isFalse();
        assertThat(tuesdayZone.chargeable()).isTrue();
        assertThat(tuesdayZone.zoneId()).isEqualTo(zoneId);
        mvc.perform(post("/api/v1/admin/parking/billing-day-calendars/" + calendarId + "/end").secure(true)
                        .cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"effectiveFrom\":\"" + monday.plusDays(7) + "\"}"))
                .andExpect(status().isOk());
        var inheritedAfterException = billingDays.resolve(zoneId, monday.plusDays(7));
        assertThat(inheritedAfterException.zoneId()).isNull();
        assertThat(inheritedAfterException.chargeable()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendars WHERE zone_id IS NULL AND normative_reference=?", Integer.class, reference)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendars WHERE zone_id=? AND normative_reference=?", Integer.class, zoneId, reference)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_days d JOIN parking.billing_day_calendars c ON c.id=d.calendar_id WHERE c.zone_id=? AND c.normative_reference=?", Integer.class, zoneId, reference)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id=? AND action IN ('CONFIGURATION_BILLING_DAYS_CREATED','CONFIGURATION_BILLING_DAYS_ENDED') AND result='SUCCESS'", Integer.class, adminId)).isEqualTo(3);
    }

    @Test
    void zoneTariffOverridesGeneralWithoutCopyingAndEndingRestoresInheritance() throws Exception {
        UUID zoneId = UUID.randomUUID();
        testZoneId = zoneId;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", zoneId, "QA-TF-" + suffix, "Tariff QA");
        OffsetDateTime starts = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0);
        String generalCode = "QA-GEN-" + suffix;
        String zoneCode = "QA-ZONE-" + suffix;
        postTariff(null, generalCode, starts).andExpect(status().isCreated());
        postTariff(zoneId, zoneCode, starts).andExpect(status().isCreated());

        var effective = mvc.perform(get("/api/v1/admin/parking/configuration/effective")
                        .param("at", starts.plusHours(1).toString()).secure(true).cookie(session))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        var snapshot = objectMapper.readTree(effective.getResponse().getContentAsString());
        var row = java.util.stream.StreamSupport.stream(snapshot.path("zones").spliterator(), false)
                .filter(item -> zoneId.toString().equals(item.path("zoneId").asText())).findFirst().orElseThrow();
        assertThat(snapshot.path("generalTariff").path("code").asText()).isEqualTo(generalCode);
        assertThat(row.path("tariffSource").asText()).isEqualTo("ZONE");
        assertThat(row.path("tariff").path("code").asText()).isEqualTo(zoneCode);
        UUID zoneTariffId = jdbc.queryForObject("SELECT id FROM parking.tariffs WHERE code=?", UUID.class, zoneCode);
        OffsetDateTime ends = starts.plusDays(4);
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + zoneTariffId + "/end").secure(true)
                        .cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"effectiveAt\":\"" + ends + "\"}"))
                .andExpect(status().isOk());
        var afterEndResponse = mvc.perform(get("/api/v1/admin/parking/configuration/effective")
                        .param("at", ends.plusHours(1).toString()).secure(true).cookie(session))
                .andExpect(status().isOk()).andReturn();
        var afterEnd = objectMapper.readTree(afterEndResponse.getResponse().getContentAsString());
        var inherited = java.util.stream.StreamSupport.stream(afterEnd.path("zones").spliterator(), false)
                .filter(item -> zoneId.toString().equals(item.path("zoneId").asText())).findFirst().orElseThrow();
        assertThat(inherited.path("tariffSource").asText()).isEqualTo("GENERAL");
        assertThat(inherited.path("tariff").path("code").asText()).isEqualTo(generalCode);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code IN (?,?) AND zone_id IS NULL", Integer.class, generalCode, zoneCode)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id=? AND action IN ('CONFIGURATION_TARIFF_CREATED','CONFIGURATION_TARIFF_ENDED') AND result='SUCCESS'", Integer.class, adminId)).isEqualTo(3);
    }

    private org.springframework.test.web.servlet.ResultActions postCalendar(UUID zoneId, LocalDate from, LocalDate to, String days) throws Exception {
        String zone = zoneId == null ? "null" : "\"" + zoneId + "\"";
        String validTo = to == null ? "null" : "\"" + to + "\"";
        String body = "{\"zoneId\":" + zone + ",\"validFrom\":\"" + from + "\",\"validTo\":" + validTo
                + ",\"chargeableDays\":" + days + ",\"normativeReference\":\"" + reference + "\"}";
        return mvc.perform(post("/api/v1/admin/parking/billing-day-calendars").secure(true).cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body));
    }

    private org.springframework.test.web.servlet.ResultActions postTariff(UUID zoneId, String code, OffsetDateTime validFrom) throws Exception {
        String zone = zoneId == null ? "null" : "\"" + zoneId + "\"";
        String body = "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"zoneId\":" + zone
                + ",\"currency\":\"USD\",\"amount\":\"1.25\",\"durationMinutes\":60,\"minMinutes\":15"
                + ",\"maxContinuousMinutes\":180,\"gracePeriodMinutes\":5,\"roundingMode\":\"HALF_UP\""
                + ",\"validFrom\":\"" + validFrom + "\",\"validTo\":null,\"normativeReference\":\"" + reference + "\"}";
        return mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body));
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }
}
