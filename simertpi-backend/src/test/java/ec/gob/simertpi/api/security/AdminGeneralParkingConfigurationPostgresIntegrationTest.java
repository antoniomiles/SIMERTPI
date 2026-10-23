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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired PlatformTransactionManager transactionManager;
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
        csrf = cookie(login, "__Host-SIMERTPI_ADMIN_XSRF");
    }

    @AfterEach
    void cleanTestConfiguration() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE parking.tariff_change_history DISABLE TRIGGER trg_tariff_history_append_only");
            jdbc.execute("ALTER TABLE parking.billing_day_calendar_history DISABLE TRIGGER trg_billing_calendar_history_append_only");
            try {
                jdbc.update("DELETE FROM parking.tariff_change_history WHERE tariff_id IN (SELECT id FROM parking.tariffs WHERE normative_reference=?)", reference);
                jdbc.update("DELETE FROM parking.tariffs WHERE normative_reference=?", reference);
                jdbc.update("DELETE FROM parking.billing_day_calendar_history WHERE calendar_id IN (SELECT id FROM parking.billing_day_calendars WHERE normative_reference LIKE ?)", reference + "%");
                jdbc.update("DELETE FROM parking.billing_day_calendars WHERE normative_reference LIKE ?", reference + "%");
                if (testZoneId != null) jdbc.update("DELETE FROM parking.zones WHERE id=?", testZoneId);
            } finally {
                jdbc.execute("ALTER TABLE parking.tariff_change_history ENABLE TRIGGER trg_tariff_history_append_only");
                jdbc.execute("ALTER TABLE parking.billing_day_calendar_history ENABLE TRIGGER trg_billing_calendar_history_append_only");
            }
        });
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
    void tariffCreationAcceptsRenewedCsrfAndRejectsMissingOrInvalidTokensWithoutWriting() throws Exception {
        String code = "QA-CSRF-TARIFF-" + UUID.randomUUID().toString().substring(0, 8);
        OffsetDateTime starts = OffsetDateTime.now(ZoneOffset.UTC).plusDays(10).withNano(0);
        String body = tariffBody(null, code, starts);

        mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(session, csrf)
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(session, csrf)
                        .header("X-XSRF-TOKEN", "incorrect-token").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code=?", Integer.class, code)).isZero();

        MvcResult refreshed = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true).cookie(session))
                .andExpect(status().isNoContent()).andReturn();
        csrf = cookie(refreshed, "__Host-SIMERTPI_ADMIN_XSRF");
        postTariff(null, code, starts).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code=?", Integer.class, code)).isEqualTo(1);
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendars WHERE zone_id IS NULL AND normative_reference=?", Integer.class, reference)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendars WHERE zone_id=? AND normative_reference=?", Integer.class, zoneId, reference)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_days d JOIN parking.billing_day_calendars c ON c.id=d.calendar_id WHERE c.zone_id=? AND c.normative_reference=?", Integer.class, zoneId, reference)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id=? AND action='CONFIGURATION_BILLING_DAYS_CREATED' AND result='SUCCESS'", Integer.class, adminId)).isEqualTo(2);
    }

    @Test
    void currentCalendarCanBeEndedWithReasonAndPreservesItsHistoricalDays() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("America/Guayaquil"));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO parking.billing_day_calendars(id,zone_id,valid_from,normative_reference,active) VALUES (?,NULL,?,?,TRUE)", id, today.minusDays(5), reference);
        for (int day=1;day<=7;day++) jdbc.update("INSERT INTO parking.billing_day_calendar_days(calendar_id,day_of_week,chargeable) VALUES (?,?,?)", id, day, day==1);
        LocalDate effectiveFrom = today.plusDays(4);
        refreshCsrf();
        mvc.perform(post("/api/v1/admin/parking/billing-day-calendars/" + id + "/end").secure(true).cookie(session, csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"effectiveFrom\":\"" + effectiveFrom + "\",\"reason\":\"Fin aprobado\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT valid_to FROM parking.billing_day_calendars WHERE id=?", LocalDate.class, id)).isEqualTo(effectiveFrom.minusDays(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_days WHERE calendar_id=? AND chargeable", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason FROM parking.billing_day_calendar_history WHERE calendar_id=? AND action='ENDED'", String.class, id)).isEqualTo("Fin aprobado");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_history WHERE calendar_id=? AND actor_id=?", Integer.class, id, adminId)).isEqualTo(1);
    }

    @Test
    void scheduledCalendarCanBeEditedInPlaceThenCancelledWithReasonAndOptimisticVersioning() throws Exception {
        LocalDate start = LocalDate.now(ZoneId.of("America/Guayaquil")).plusDays(15);
        MvcResult created = postCalendar(null, start, null, "[1,2]").andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).asText());
        String update = "{\"validFrom\":\"" + start.plusDays(2) + "\",\"validTo\":null,\"chargeableDays\":[3,4],"
                + "\"normativeReference\":\"" + reference + "-UPDATED\",\"reason\":\"Corrección aprobada\",\"expectedVersion\":0}";
        refreshCsrf();
        MvcResult updated = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/admin/parking/billing-day-calendars/" + id).secure(true).cookie(session, csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(update))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(updated.getResponse().getContentAsString()).path("id").asText()).isEqualTo(id.toString());
        assertThat(jdbc.queryForObject("SELECT row_version FROM parking.billing_day_calendars WHERE id=?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_days WHERE calendar_id=? AND chargeable", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reason FROM parking.billing_day_calendar_history WHERE calendar_id=? AND action='SCHEDULED_UPDATED'", String.class, id)).isEqualTo("Corrección aprobada");

        refreshCsrf();
        mvc.perform(post("/api/v1/admin/parking/billing-day-calendars/" + id + "/cancel").secure(true).cookie(session, csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"reason\":\"Se reemplaza la programación\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT active FROM parking.billing_day_calendars WHERE id=?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM parking.billing_day_calendars WHERE id=?", String.class, id)).isEqualTo("Se reemplaza la programación");
        assertThat(billingDays.resolve(null, start.plusDays(3)).configured()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_history WHERE calendar_id=? AND action IN ('CREATED','SCHEDULED_UPDATED','SCHEDULED_CANCELLED')", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_history WHERE calendar_id=? AND actor_id=?", Integer.class, id, adminId)).isEqualTo(3);

        refreshCsrf();
        mvc.perform(post("/api/v1/admin/parking/billing-day-calendars/" + id + "/cancel").secure(true).cookie(session, csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"reason\":\"Duplicada\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void currentCalendarRevisionIsAtomicAndPreservesPreviousDaysAndVersion() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("America/Guayaquil"));
        LocalDate starts = today.minusDays(5);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO parking.billing_day_calendars(id,zone_id,valid_from,normative_reference,active) VALUES (?,NULL,?,?,TRUE)", id, starts, reference);
        for (int day=1;day<=7;day++) jdbc.update("INSERT INTO parking.billing_day_calendar_days(calendar_id,day_of_week,chargeable) VALUES (?,?,?)", id, day, day==today.getDayOfWeek().getValue());
        LocalDate newStart=today.plusDays(3);
        String body="{\"validFrom\":\""+newStart+"\",\"validTo\":null,\"chargeableDays\":[],\"normativeReference\":\""+reference+"-NEXT\",\"reason\":\"Actualización aprobada\",\"expectedVersion\":0}";
        refreshCsrf();
        MvcResult response=mvc.perform(post("/api/v1/admin/parking/billing-day-calendars/"+id+"/revise").secure(true).cookie(session,csrf)
                        .header("X-XSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        UUID newId=UUID.fromString(objectMapper.readTree(response.getResponse().getContentAsString()).path("id").asText());
        assertThat(newId).isNotEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT valid_to FROM parking.billing_day_calendars WHERE id=?",LocalDate.class,id)).isEqualTo(newStart.minusDays(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_days WHERE calendar_id=? AND chargeable",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT previous_calendar_id FROM parking.billing_day_calendars WHERE id=?",UUID.class,newId)).isEqualTo(id);
        assertThat(billingDays.resolve(null,today).chargeable()).isTrue();
        assertThat(billingDays.resolve(null,newStart).chargeable()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendar_history h JOIN parking.billing_day_calendars c ON c.id=h.calendar_id WHERE c.version_group_id=(SELECT version_group_id FROM parking.billing_day_calendars WHERE id=?)",Integer.class,id)).isEqualTo(2);
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
        OffsetDateTime zoneStart = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).withNano(0);
        UUID zoneTariffId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,
                    duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,valid_to,normative_reference)
                VALUES (?,?,?,?,'USD','HALF_UP',0,0.75,60,15,240,TRUE,?,NULL,?)
                """, zoneTariffId, zoneCode, "Tarifa zonal vigente QA", zoneId, zoneStart, reference);

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
        Long expectedVersion = jdbc.queryForObject("SELECT row_version FROM parking.tariffs WHERE id=?", Long.class, zoneTariffId);
        OffsetDateTime ends = starts.plusDays(4);
        refreshCsrf();
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + zoneTariffId + "/end").secure(true)
                        .cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"effectiveAt\":\"" + ends + "\",\"reason\":\"Fin de prueba QA\",\"expectedVersion\":" + expectedVersion + "}"))
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE actor_id=? AND action IN ('CONFIGURATION_TARIFF_CREATED','CONFIGURATION_TARIFF_ENDED') AND result='SUCCESS'", Integer.class, adminId)).isEqualTo(2);
    }

    @Test
    void effectiveConfigurationResolvesLegacyOverlappingTariffsWhenNoBillingCalendarExists() throws Exception {
        UUID zoneId = UUID.randomUUID();
        testZoneId = zoneId;
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", zoneId, "QA-EF-" + suffix, "Effective config QA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.billing_day_calendars", Integer.class)).isZero();

        OffsetDateTime earlier = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime later = OffsetDateTime.parse("2026-01-02T00:00:00Z");
        insertLegacyActiveTariff(zoneId, "QA-OLD-" + suffix, new java.math.BigDecimal("0.80"), earlier);
        insertLegacyActiveTariff(zoneId, "QA-NEW-" + suffix, new java.math.BigDecimal("0.25"), later);

        MvcResult result = mvc.perform(get("/api/v1/admin/parking/configuration/effective")
                        .param("at", "2026-10-20T16:56:05.999Z").secure(true).cookie(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        var snapshot = objectMapper.readTree(result.getResponse().getContentAsString());
        var row = java.util.stream.StreamSupport.stream(snapshot.path("zones").spliterator(), false)
                .filter(item -> zoneId.toString().equals(item.path("zoneId").asText())).findFirst().orElseThrow();
        assertThat(row.path("tariff").path("code").asText()).isEqualTo("QA-NEW-" + suffix);
        assertThat(row.path("tariff").path("amount").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(row.path("billingDaysConfigured").asBoolean()).isFalse();
        assertThat(row.path("chargeable").asBoolean()).isTrue();
        assertThat(row.path("billingDaysSource").asText()).isEqualTo("LEGACY_UNCONFIGURED");
    }

    private void insertLegacyActiveTariff(UUID zoneId, String code, java.math.BigDecimal amount, OffsetDateTime validFrom) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,
                    amount,duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,valid_to,created_at,updated_at,normative_reference)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,TRUE,?,NULL,?,?,?)
                """, UUID.randomUUID(), code, code, zoneId, "USD", "HALF_UP", 5, amount, 60, 15, 180,
                validFrom, now, now, reference);
    }

    private org.springframework.test.web.servlet.ResultActions postCalendar(UUID zoneId, LocalDate from, LocalDate to, String days) throws Exception {
        String zone = zoneId == null ? "null" : "\"" + zoneId + "\"";
        String validTo = to == null ? "null" : "\"" + to + "\"";
        String body = "{\"zoneId\":" + zone + ",\"validFrom\":\"" + from + "\",\"validTo\":" + validTo
                + ",\"chargeableDays\":" + days + ",\"normativeReference\":\"" + reference + "\"}";
        refreshCsrf();
        return mvc.perform(post("/api/v1/admin/parking/billing-day-calendars").secure(true).cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body));
    }

    private org.springframework.test.web.servlet.ResultActions postTariff(UUID zoneId, String code, OffsetDateTime validFrom) throws Exception {
        refreshCsrf();
        return mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(tariffBody(zoneId, code, validFrom)));
    }

    private void refreshCsrf() throws Exception {
        MvcResult refreshed = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true).cookie(session))
                .andExpect(status().isNoContent()).andReturn();
        csrf = cookie(refreshed, "__Host-SIMERTPI_ADMIN_XSRF");
    }

    private String tariffBody(UUID zoneId, String code, OffsetDateTime validFrom) {
        String zone = zoneId == null ? "null" : "\"" + zoneId + "\"";
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"zoneId\":" + zone
                + ",\"currency\":\"USD\",\"amount\":\"1.25\",\"durationMinutes\":60,\"minMinutes\":15"
                + ",\"maxContinuousMinutes\":180,\"gracePeriodMinutes\":5,\"roundingMode\":\"HALF_UP\""
                + ",\"validFrom\":\"" + validFrom + "\",\"validTo\":null,\"normativeReference\":\"" + reference + "\"}";
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }
}
