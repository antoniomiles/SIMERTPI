package ec.gob.simertpi.api.security;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"logging.level.root=WARN", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc
class AdminParkingTariffSchedulePostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String PASSWORD = "isolated-tariff-admin-password-2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    private String username;
    private Cookie sessionCookie;

    @BeforeEach
    void createAdministratorSession() throws Exception {
        username = "tariff-admin-" + UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                adminId, username, username + "@example.invalid", encoder.encode(PASSWORD), "Tariff", "Admin");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'", adminId);
        MvcResult csrf = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn();
        Cookie csrfCookie = cookie(csrf, "__Host-SIMERTPI_ADMIN_XSRF");
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()).contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        sessionCookie = cookie(login, AdminAuthenticationService.COOKIE);
    }

    @Test
    void tariffScheduleAndCalendarReadsRequireAdministrativeSessionAndNoStore() throws Exception {
        for (String path : new String[]{"tariffs", "schedules", "calendar-exceptions"}) {
            String url = "/api/v1/admin/parking/" + path;
            mvc.perform(get(url).secure(true)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).secure(true).with(httpBasic(username, PASSWORD))).andExpect(status().isUnauthorized());
            mvc.perform(get(url).secure(true).cookie(sessionCookie)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        }
    }

    @Test
    void inspectorCannotObtainAdministrativeSessionOrReadConfiguration() throws Exception {
        String inspector = "tariff-inspector-" + UUID.randomUUID();
        UUID inspectorId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,TRUE)",
                inspectorId, inspector, inspector + "@example.invalid", encoder.encode(PASSWORD), "Test", "Inspector");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='INSPECTOR'", inspectorId);
        MvcResult csrf = mvc.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn();
        Cookie csrfCookie = cookie(csrf, "__Host-SIMERTPI_ADMIN_XSRF");
        MvcResult login = mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()).contentType("application/json")
                        .content("{\"username\":\"" + inspector + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        org.assertj.core.api.Assertions.assertThat(login.getResponse().getHeaders("Set-Cookie"))
                .noneMatch(value -> value.startsWith(AdminAuthenticationService.COOKIE + "="));
        mvc.perform(get("/api/v1/admin/parking/tariffs").secure(true)).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsRealConfigurationAndKeepsPublicTariffContractUnchanged() throws Exception {
        UUID zoneId = UUID.randomUUID();
        UUID tariffId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        UUID holidayId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", zoneId, "QA-TZ-" + suffix, "Zona QA tarifas");
        jdbc.update("INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,duration_minutes,min_minutes,max_continuous_minutes,active,valid_from) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                tariffId, "QA-TF-" + suffix, "Tarifa QA", zoneId, "USD", "HALF_UP", 0,
                new java.math.BigDecimal("0.50"), 30, 15, 240, true);
        jdbc.update("INSERT INTO parking.schedules(id,zone_id,day_of_week,start_time,end_time,active,valid_from) VALUES (?,?,1,'08:00','17:00',TRUE,CURRENT_DATE)", scheduleId, zoneId);
        jdbc.update("INSERT INTO parking.holidays(id,holiday_date,name,zone_id,holiday_type,tariffed,start_time,end_time,normative_reference,valid_from,active) VALUES (?,CURRENT_DATE,?,?,'SPECIAL_SCHEDULE',TRUE,'09:00','13:00','QA fixture',CURRENT_DATE,TRUE)", holidayId, "Excepción QA", zoneId);

        MvcResult tariffs = mvc.perform(get("/api/v1/admin/parking/tariffs").secure(true).cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn();
        JsonNode tariff = record(tariffs, tariffId);
        org.assertj.core.api.Assertions.assertThat(tariff.path("zoneCode").asText()).isEqualTo("QA-TZ-" + suffix);
        org.assertj.core.api.Assertions.assertThat(tariff.path("amount").decimalValue()).isEqualByComparingTo("0.50");
        org.assertj.core.api.Assertions.assertThat(tariff.path("gracePeriodMinutes").asInt()).isZero();

        MvcResult schedules = mvc.perform(get("/api/v1/admin/parking/schedules").secure(true).cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn();
        JsonNode schedule = record(schedules, scheduleId);
        org.assertj.core.api.Assertions.assertThat(schedule.path("zoneName").asText()).isEqualTo("Zona QA tarifas");
        org.assertj.core.api.Assertions.assertThat(schedule.path("startTime").asText()).startsWith("08:00");

        MvcResult exceptions = mvc.perform(get("/api/v1/admin/parking/calendar-exceptions").secure(true).cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn();
        org.assertj.core.api.Assertions.assertThat(record(exceptions, holidayId).path("normativeReference").asText())
                .isEqualTo("QA fixture");

        MvcResult publicTariffs = mvc.perform(get("/api/v1/tariffs").secure(true))
                .andExpect(status().isOk()).andReturn();
        JsonNode publicTariff = record(publicTariffs, tariffId);
        org.assertj.core.api.Assertions.assertThat(publicTariff.has("zoneId")).isFalse();
        org.assertj.core.api.Assertions.assertThat(publicTariff.has("currency")).isFalse();
    }

    @Test
    void legacyTariffWriteIsDeniedSoAdministrativeChangesUseVersionedFlow() throws Exception {
        String code = "QA-LEGACY-DENIED-" + UUID.randomUUID().toString().substring(0, 8);
        String request = """
                {"code":"%s","name":"Tarifa legado","amount":0.50,"durationMinutes":30,
                 "minMinutes":15,"maxContinuousMinutes":240,
                 "validFrom":"%s","validTo":null}
                """.formatted(code, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1));

        mvc.perform(post("/api/v1/tariffs").secure(true).with(httpBasic(username, PASSWORD))
                        .contentType("application/json").content(request))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking.tariffs WHERE code=?", Long.class, code)).isZero();
    }

    @Test
    void scheduledTariffCanBeEditedAndCancelledWithReasonAuditAndNoFutureActivation() throws Exception {
        java.time.OffsetDateTime start = java.time.OffsetDateTime.now(java.time.ZoneId.of("America/Guayaquil"))
                .plusDays(1).withSecond(0).withNano(0);
        String code = "QA-SCHED-" + UUID.randomUUID().toString().substring(0, 8);
        UUID zoneId = insertTestZone();
        String body = tariffBody(code, "0.25", start, zoneId);
        Cookie csrf = csrfCookie();
        MvcResult created = mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true)
                        .cookie(sessionCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode createdJson = json.readTree(created.getResponse().getContentAsString());
        UUID id = UUID.fromString(createdJson.path("id").asText());

        java.time.OffsetDateTime movedStart = start.plusDays(1);
        Cookie editCsrf = csrfCookie();
        MvcResult edited = mvc.perform(put("/api/v1/admin/parking/tariffs/" + id).secure(true)
                        .cookie(sessionCookie, editCsrf).header("X-XSRF-TOKEN", editCsrf.getValue())
                        .contentType("application/json").content(tariffBody(code, "0.75", movedStart, zoneId,
                                createdJson.path("rowVersion").asLong(), "Corrección de programación QA")))
                .andExpect(status().isOk()).andReturn();
        JsonNode editedJson = json.readTree(edited.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(editedJson.path("id").asText()).isEqualTo(id.toString());
        org.assertj.core.api.Assertions.assertThat(editedJson.path("amount").decimalValue()).isEqualByComparingTo("0.75");
        org.assertj.core.api.Assertions.assertThat(editedJson.path("validFrom").asText()).contains(movedStart.toInstant().toString());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code LIKE 'QA-SCHED-%'", Integer.class)).isEqualTo(1);

        Cookie cancelCsrf = csrfCookie();
        MvcResult cancelled = mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/cancel").secure(true)
                        .cookie(sessionCookie, cancelCsrf).header("X-XSRF-TOKEN", cancelCsrf.getValue())
                        .contentType("application/json").content("{\"reason\":\"Importe programado incorrecto\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode cancelledJson = json.readTree(cancelled.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(cancelledJson.path("active").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(cancelledJson.path("cancelledAt").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(cancelledJson.path("cancelledByUserId").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(cancelledJson.path("cancellationReason").asText()).isEqualTo("Importe programado incorrecto");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE resource_id=? AND action='CONFIGURATION_SCHEDULED_TARIFF_UPDATED' AND result='SUCCESS'", Integer.class, id)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE resource_id=? AND action='CONFIGURATION_SCHEDULED_TARIFF_CANCELLED' AND result='SUCCESS' AND actor_id=?", Integer.class, id,
                jdbc.queryForObject("SELECT id FROM identity.users WHERE username=?", UUID.class, username))).isEqualTo(1);

        java.time.OffsetDateTime afterStart = movedStart.plusMinutes(1);
        MvcResult effective = mvc.perform(get("/api/v1/admin/parking/configuration/effective").secure(true)
                        .cookie(sessionCookie).param("at", afterStart.toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode effectiveJson = json.readTree(effective.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(effectiveJson.path("zones").findValuesAsText("id")).doesNotContain(id.toString());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT active FROM parking.tariffs WHERE id=?", Boolean.class, id)).isFalse();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void cancellationRequiresReasonAndMakesScheduledTariffTerminal() throws Exception {
        java.time.OffsetDateTime start = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(2).withSecond(0).withNano(0);
        String code = "QA-CANCEL-" + UUID.randomUUID().toString().substring(0, 8);
        UUID zoneId = insertTestZone();
        Cookie createCsrf = csrfCookie();
        MvcResult created = mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true)
                        .cookie(sessionCookie, createCsrf).header("X-XSRF-TOKEN", createCsrf.getValue())
                        .contentType("application/json").content(tariffBody(code, "0.25", start, zoneId)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).path("id").asText());
        long version = json.readTree(created.getResponse().getContentAsString()).path("rowVersion").asLong();

        Cookie missingReasonCsrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/cancel").secure(true)
                        .cookie(sessionCookie, missingReasonCsrf).header("X-XSRF-TOKEN", missingReasonCsrf.getValue())
                        .contentType("application/json").content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT active AND cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancellation_reason IS NULL FROM parking.tariffs WHERE id=?",
                Boolean.class, id)).isTrue();

        Cookie scheduledEndCsrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/end").secure(true)
                        .cookie(sessionCookie, scheduledEndCsrf).header("X-XSRF-TOKEN", scheduledEndCsrf.getValue())
                        .contentType("application/json").content("{\"effectiveAt\":\"" + start.plusDays(3)
                                + "\",\"reason\":\"Fin futura\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isConflict());

        Cookie cancelCsrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/cancel").secure(true)
                        .cookie(sessionCookie, cancelCsrf).header("X-XSRF-TOKEN", cancelCsrf.getValue())
                        .contentType("application/json").content("{\"reason\":\"Prueba terminal\"}"))
                .andExpect(status().isOk());

        Cookie editCsrf = csrfCookie();
        mvc.perform(put("/api/v1/admin/parking/tariffs/" + id).secure(true)
                        .cookie(sessionCookie, editCsrf).header("X-XSRF-TOKEN", editCsrf.getValue())
                        .contentType("application/json").content(tariffBody(code, "0.50", start.plusDays(1), zoneId)))
                .andExpect(status().isConflict());
        Cookie repeatCancelCsrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/cancel").secure(true)
                        .cookie(sessionCookie, repeatCancelCsrf).header("X-XSRF-TOKEN", repeatCancelCsrf.getValue())
                        .contentType("application/json").content("{\"reason\":\"Segundo intento\"}"))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking.tariffs WHERE id=? AND active=FALSE AND cancellation_reason='Prueba terminal'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void scheduledTariffRejectsOverlapsAndCannotBeEditedAfterStarting() throws Exception {
        java.time.OffsetDateTime start = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(3).withSecond(0).withNano(0);
        String code = "QA-OVERLAP-" + UUID.randomUUID().toString().substring(0, 8);
        UUID zoneId = insertTestZone();
        Cookie createCsrf = csrfCookie();
        MvcResult created = mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(sessionCookie, createCsrf)
                        .header("X-XSRF-TOKEN", createCsrf.getValue()).contentType("application/json").content(tariffBody(code, "0.25", start, zoneId)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).path("id").asText());
        Cookie overlapCsrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(sessionCookie, overlapCsrf)
                        .header("X-XSRF-TOKEN", overlapCsrf.getValue()).contentType("application/json")
                        .content(tariffBody(code + "-2", "0.50", start.plusHours(1), zoneId)))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE parking.tariffs SET valid_from=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=?", id);
        Cookie editCsrf = csrfCookie();
        mvc.perform(put("/api/v1/admin/parking/tariffs/" + id).secure(true).cookie(sessionCookie, editCsrf)
                        .header("X-XSRF-TOKEN", editCsrf.getValue()).contentType("application/json")
                        .content(tariffBody(code, "0.75", start.plusDays(1), zoneId)))
                .andExpect(status().isConflict());
    }

    @Test
    void revisingCurrentTariffCreatesFutureVersionAndPreservesSessionAndPaymentSnapshots() throws Exception {
        UUID zoneId = insertTestZone();
        String code = "QA-CURRENT-" + UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0);
        UUID currentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,
                    duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,created_at,updated_at)
                VALUES (?,?,?,?,'USD','HALF_UP',5,0.50,60,15,240,TRUE,?,?,?)
                """, currentId, code, "Tarifa vigente QA", zoneId, now.minusDays(2), now.minusDays(2), now.minusDays(2));

        UUID userId = jdbc.queryForObject("SELECT id FROM identity.users WHERE username=?", UUID.class, username);
        UUID streetId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name,active) VALUES (?,?,?, ?,TRUE)",
                streetId, zoneId, "QA-ST-" + suffix, "Calle QA");
        jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,active) VALUES (?,?,?,?,?,TRUE)",
                spaceId, streetId, "QA-SP-" + suffix, "QA-QR-" + suffix, suffix);
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,active) VALUES (?,?,?,TRUE)",
                vehicleId, userId, "Q" + suffix);
        jdbc.update("""
                INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,tariff_id,started_at,
                    expected_end_at,status,total_amount)
                VALUES (?,?,?,?,?,?,?,'COMPLETED',0.50)
                """, sessionId, userId, vehicleId, spaceId, currentId, now.minusHours(1), now);
        jdbc.update("""
                INSERT INTO payments.payments(id,parking_session_id,provider,idempotency_key,amount,currency,status)
                VALUES (?,?, 'QA', ?,0.50,'USD','PENDING')
                """, paymentId, sessionId, "qa-tariff-revision-" + suffix);

        long version = jdbc.queryForObject("SELECT row_version FROM parking.tariffs WHERE id=?", Long.class, currentId);
        java.time.OffsetDateTime effectiveAt = java.time.OffsetDateTime.now(java.time.ZoneId.of("America/Guayaquil"))
                .plusDays(2).withSecond(0).withNano(0);
        Cookie csrf = csrfCookie();
        MvcResult revised = mvc.perform(post("/api/v1/admin/parking/tariffs/" + currentId + "/revise").secure(true)
                        .cookie(sessionCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content(tariffBody(code, "0.75", effectiveAt, zoneId, version, "Importe actualizado por resolución QA")))
                .andExpect(status().isOk()).andReturn();
        JsonNode next = json.readTree(revised.getResponse().getContentAsString());
        UUID nextId = UUID.fromString(next.path("id").asText());
        org.assertj.core.api.Assertions.assertThat(nextId).isNotEqualTo(currentId);
        org.assertj.core.api.Assertions.assertThat(next.path("previousVersionId").asText()).isEqualTo(currentId.toString());
        org.assertj.core.api.Assertions.assertThat(next.path("versionGroupId").asText())
                .isEqualTo(jdbc.queryForObject("SELECT version_group_id FROM parking.tariffs WHERE id=?", UUID.class, currentId).toString());

        java.time.OffsetDateTime oldEnd = jdbc.queryForObject("SELECT valid_to FROM parking.tariffs WHERE id=?",
                java.time.OffsetDateTime.class, currentId);
        org.assertj.core.api.Assertions.assertThat(oldEnd).isEqualTo(effectiveAt.minusNanos(1_000));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT amount FROM parking.tariffs WHERE id=?", java.math.BigDecimal.class, currentId))
                .isEqualByComparingTo("0.50");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT tariff_id FROM parking.parking_sessions WHERE id=?", UUID.class, sessionId))
                .isEqualTo(currentId);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT amount FROM payments.payments WHERE id=?", java.math.BigDecimal.class, paymentId))
                .isEqualByComparingTo("0.50");

        JsonNode beforeBoundary = effectiveAt(effectiveAt.minusNanos(1_000));
        org.assertj.core.api.Assertions.assertThat(zoneTariff(beforeBoundary, zoneId).path("id").asText()).isEqualTo(currentId.toString());
        JsonNode atBoundary = effectiveAt(effectiveAt);
        org.assertj.core.api.Assertions.assertThat(zoneTariff(atBoundary, zoneId).path("id").asText()).isEqualTo(nextId.toString());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tariff_change_history
                 WHERE tariff_id=? AND action='REVISED' AND actor_id=(SELECT id FROM identity.users WHERE username=?)
                   AND reason=? AND before_state->>'amount'='0.50' AND after_state->>'amount'='0.75'
                """, Integer.class, nextId, username, "Importe actualizado por resolución QA")).isEqualTo(1);

        Cookie staleCsrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + currentId + "/revise").secure(true)
                        .cookie(sessionCookie, staleCsrf).header("X-XSRF-TOKEN", staleCsrf.getValue()).contentType("application/json")
                        .content(tariffBody(code, "0.80", effectiveAt.plusDays(1), zoneId, version, "Reintento con versión desactualizada")))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE version_group_id=(SELECT version_group_id FROM parking.tariffs WHERE id=?)", Integer.class, currentId)).isEqualTo(2);
    }

    @Test
    void currentTariffRevisionRejectsAnonymousAndMissingCsrfRequests() throws Exception {
        UUID tariffId = UUID.randomUUID();
        UUID zoneId = insertTestZone();
        String code = "QA-SECURE-" + UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0);
        jdbc.update("""
                INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,
                    duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,created_at,updated_at)
                VALUES (?,?,?,?,'USD','HALF_UP',0,0.50,60,15,240,TRUE,?,?,?)
                """, tariffId, code, "Tarifa protegida QA", zoneId, now.minusDays(1), now.minusDays(1), now.minusDays(1));
        String request = tariffBody(code, "0.75", now.plusDays(2), zoneId, 0L, "Motivo de prueba");
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + tariffId + "/revise").secure(true)
                        .contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + tariffId + "/revise").secure(true)
                        .cookie(sessionCookie).contentType("application/json").content(request)).andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE code=?", Integer.class, code)).isEqualTo(1);
    }

    @Test
    void immediateRevisionUsesDatabaseInstantAndPreservesExistingSessionAndPayment() throws Exception {
        UUID tariffId = UUID.randomUUID();
        UUID zoneId = insertTestZone();
        String code = "QA-NOW-" + UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime prior = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,
                    duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,created_at,updated_at)
                VALUES (?,?,?,?,'USD','HALF_UP',0,0.50,60,15,240,TRUE,?,?,?)
                """, tariffId, code, "Tarifa vigente QA", zoneId, prior, prior, prior);
        UUID userId = jdbc.queryForObject("SELECT id FROM identity.users WHERE username=?", UUID.class, username);
        UUID sessionId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate,active) VALUES (?,?,?,TRUE)",
                vehicleId, userId, "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 7));
        UUID streetId = UUID.randomUUID();
        jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name,active) VALUES (?,?,?, ?,TRUE)",
                streetId, zoneId, "QA-NOW-ST-" + UUID.randomUUID().toString().substring(0, 6), "Calle QA");
        UUID spaceId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number,active) VALUES (?,?,?,?,?,TRUE)",
                spaceId, streetId, "QA-NOW-SP-" + suffix, "QA-NOW-QR-" + suffix, suffix);
        jdbc.update("""
                INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,tariff_id,started_at,expected_end_at,status,total_amount)
                VALUES (?,?,?,?,?,?,?,'PENDING_PAYMENT',0.50)
                """, sessionId, userId, vehicleId, spaceId, tariffId, prior.plusHours(1), prior.plusHours(2));
        jdbc.update("""
                INSERT INTO payments.payments(id,parking_session_id,provider,idempotency_key,amount,currency,status)
                VALUES (?,?, 'QA', ?,0.50,'USD','PENDING')
                """, paymentId, sessionId, "qa-tariff-immediate-" + suffix);
        long version = jdbc.queryForObject("SELECT row_version FROM parking.tariffs WHERE id=?", Long.class, tariffId);
        Cookie csrf = csrfCookie();
        MvcResult revised = mvc.perform(post("/api/v1/admin/parking/tariffs/" + tariffId + "/revise-now").secure(true)
                        .cookie(sessionCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content(immediateTariffBody(code, "0.75", version, "Corrección inmediata respaldada por referencia QA")))
                .andExpect(status().isOk()).andReturn();
        JsonNode next = json.readTree(revised.getResponse().getContentAsString());
        UUID nextId = UUID.fromString(next.path("id").asText());
        java.time.OffsetDateTime boundary = java.time.OffsetDateTime.parse(next.path("validFrom").asText());
        java.time.OffsetDateTime previousEnd = jdbc.queryForObject("SELECT valid_to FROM parking.tariffs WHERE id=?",
                java.time.OffsetDateTime.class, tariffId);
        org.assertj.core.api.Assertions.assertThat(boundary.getNano() % 1_000).isZero();
        org.assertj.core.api.Assertions.assertThat(previousEnd).isEqualTo(boundary.minusNanos(1_000));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT amount FROM parking.tariffs WHERE id=?", java.math.BigDecimal.class, tariffId))
                .isEqualByComparingTo("0.50");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT tariff_id FROM parking.parking_sessions WHERE id=?", UUID.class, sessionId))
                .isEqualTo(tariffId);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT amount FROM payments.payments WHERE id=?", java.math.BigDecimal.class, paymentId))
                .isEqualByComparingTo("0.50");
        org.assertj.core.api.Assertions.assertThat(zoneTariff(effectiveAt(previousEnd), zoneId).path("id").asText()).isEqualTo(tariffId.toString());
        org.assertj.core.api.Assertions.assertThat(zoneTariff(effectiveAt(boundary), zoneId).path("id").asText()).isEqualTo(nextId.toString());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tariff_change_history WHERE tariff_id=? AND action='REVISED_IMMEDIATELY'
                  AND actor_id=(SELECT id FROM identity.users WHERE username=?) AND reason=? AND effective_at=?
                """, Integer.class, nextId, username, "Corrección inmediata respaldada por referencia QA", boundary)).isEqualTo(1);
    }

    @Test
    void immediateEndRequiresReasonAndLeavesZoneUnconfiguredWithoutReplacement() throws Exception {
        UUID tariffId = UUID.randomUUID();
        UUID zoneId = insertTestZone();
        String code = "QA-END-NOW-" + UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime prior = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,
                    duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,created_at,updated_at)
                VALUES (?,?,?,?,'USD','HALF_UP',0,0.50,60,15,240,TRUE,?,?,?)
                """, tariffId, code, "Tarifa para finalizar QA", zoneId, prior, prior, prior);
        long version = jdbc.queryForObject("SELECT row_version FROM parking.tariffs WHERE id=?", Long.class, tariffId);
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + tariffId + "/end-now").secure(true)
                        .cookie(sessionCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"reason\":\" \" ,\"expectedVersion\":" + version + "}"))
                .andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT valid_to FROM parking.tariffs WHERE id=?", java.time.OffsetDateTime.class, tariffId)).isNull();

        MvcResult ended = mvc.perform(post("/api/v1/admin/parking/tariffs/" + tariffId + "/end-now").secure(true)
                        .cookie(sessionCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json")
                        .content("{\"reason\":\"Cese inmediato respaldado por QA\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isOk()).andReturn();
        java.time.OffsetDateTime effectiveAt = jdbc.queryForObject("SELECT effective_at FROM parking.tariff_change_history WHERE tariff_id=? AND action='ENDED_IMMEDIATELY'", java.time.OffsetDateTime.class, tariffId);
        java.time.OffsetDateTime priorEnd = jdbc.queryForObject("SELECT valid_to FROM parking.tariffs WHERE id=?", java.time.OffsetDateTime.class, tariffId);
        org.assertj.core.api.Assertions.assertThat(priorEnd).isEqualTo(effectiveAt.minusNanos(1_000));
        org.assertj.core.api.Assertions.assertThat(zoneTariff(effectiveAt(priorEnd), zoneId).path("id").asText()).isEqualTo(tariffId.toString());
        org.assertj.core.api.Assertions.assertThat(zoneTariff(effectiveAt(effectiveAt), zoneId).isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT active FROM parking.tariffs WHERE id=?", Boolean.class, tariffId)).isTrue();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tariff_change_history WHERE tariff_id=? AND action='ENDED_IMMEDIATELY'
                  AND actor_id=(SELECT id FROM identity.users WHERE username=?) AND reason='Cese inmediato respaldado por QA'
                """, Integer.class, tariffId, username)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(json.readTree(ended.getResponse().getContentAsString()).path("id").asText()).isEqualTo(tariffId.toString());
    }

    @Test
    void concurrentImmediateRevisionsAcceptOnlyOneExpectedVersion() throws Exception {
        UUID tariffId = UUID.randomUUID();
        UUID zoneId = insertTestZone();
        String code = "QA-RACE-NOW-" + UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime prior = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,
                    duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,created_at,updated_at)
                VALUES (?,?,?,?,'USD','HALF_UP',0,0.50,60,15,240,TRUE,?,?,?)
                """, tariffId, code, "Tarifa concurrencia QA", zoneId, prior, prior, prior);
        long version = jdbc.queryForObject("SELECT row_version FROM parking.tariffs WHERE id=?", Long.class, tariffId);
        String body = immediateTariffBody(code, "0.75", version, "Cambio concurrente de prueba QA");
        Cookie csrf = csrfCookie();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            var one = pool.submit(() -> { gate.await(); return mvc.perform(post("/api/v1/admin/parking/tariffs/" + tariffId + "/revise-now").secure(true)
                    .cookie(sessionCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body)).andReturn().getResponse().getStatus(); });
            var two = pool.submit(() -> { gate.await(); return mvc.perform(post("/api/v1/admin/parking/tariffs/" + tariffId + "/revise-now").secure(true)
                    .cookie(sessionCookie, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body)).andReturn().getResponse().getStatus(); });
            gate.countDown();
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(one.get(), two.get()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { pool.shutdownNow(); }
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariffs WHERE version_group_id=(SELECT version_group_id FROM parking.tariffs WHERE id=?)", Integer.class, tariffId)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariff_change_history WHERE action='REVISED_IMMEDIATELY' AND tariff_id<>(?) AND previous_tariff_id=?", Integer.class, tariffId, tariffId)).isEqualTo(1);
    }

    @Test
    void firstScheduledTariffCanChangeZoneWhenNewScopeHasNoConflictingValidity() throws Exception {
        UUID originalZone = insertTestZone();
        UUID newZone = insertTestZone();
        String code = "QA-MOVE-" + UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime start = java.time.OffsetDateTime.now(java.time.ZoneId.of("America/Guayaquil"))
                .plusDays(5).withSecond(0).withNano(0);
        Cookie createCsrf = csrfCookie();
        MvcResult created = mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(sessionCookie, createCsrf)
                        .header("X-XSRF-TOKEN", createCsrf.getValue()).contentType("application/json")
                        .content(tariffBody(code, "0.25", start, originalZone)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode initial = json.readTree(created.getResponse().getContentAsString());
        UUID id = UUID.fromString(initial.path("id").asText());
        Cookie updateCsrf = csrfCookie();
        MvcResult moved = mvc.perform(put("/api/v1/admin/parking/tariffs/" + id).secure(true)
                        .cookie(sessionCookie, updateCsrf).header("X-XSRF-TOKEN", updateCsrf.getValue())
                        .contentType("application/json").content(tariffBody(code, "0.25", start, newZone,
                                initial.path("rowVersion").asLong(), "Corrección de zona antes de vigencia")))
                .andExpect(status().isOk()).andReturn();
        JsonNode result = json.readTree(moved.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(result.path("id").asText()).isEqualTo(id.toString());
        org.assertj.core.api.Assertions.assertThat(result.path("zoneId").asText()).isEqualTo(newZone.toString());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM parking.tariff_change_history WHERE tariff_id=? AND action='SCHEDULED_UPDATED'
                  AND reason=? AND before_state->>'zoneId'=? AND after_state->>'zoneId'=?
                """, Integer.class, id, "Corrección de zona antes de vigencia", originalZone.toString(), newZone.toString())).isEqualTo(1);
    }

    @Test
    void scheduledTariffCannotMoveIntoAZoneWithAnOverlappingTariff() throws Exception {
        UUID originalZone = insertTestZone();
        UUID occupiedZone = insertTestZone();
        String code = "QA-MOVE-CONFLICT-" + UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime start = java.time.OffsetDateTime.now(java.time.ZoneId.of("America/Guayaquil"))
                .plusDays(5).withSecond(0).withNano(0);
        Cookie createCsrf = csrfCookie();
        MvcResult created = mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(sessionCookie, createCsrf)
                        .header("X-XSRF-TOKEN", createCsrf.getValue()).contentType("application/json")
                        .content(tariffBody(code, "0.25", start, originalZone)))
                .andExpect(status().isCreated()).andReturn();
        UUID scheduledId = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).path("id").asText());
        UUID conflictingId = UUID.randomUUID();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0);
        jdbc.update("""
                INSERT INTO parking.tariffs(id,code,name,zone_id,currency,rounding_mode,grace_period_minutes,amount,
                    duration_minutes,min_minutes,max_continuous_minutes,active,valid_from,valid_to,created_at,updated_at)
                VALUES (?,?,?,?,'USD','HALF_UP',0,0.50,60,15,240,TRUE,?,NULL,?,?)
                """, conflictingId, "QA-OCCUPIED-" + UUID.randomUUID().toString().substring(0, 8),
                "Tarifa existente QA", occupiedZone, now.minusDays(1), now.minusDays(1), now.minusDays(1));

        Cookie updateCsrf = csrfCookie();
        mvc.perform(put("/api/v1/admin/parking/tariffs/" + scheduledId).secure(true)
                        .cookie(sessionCookie, updateCsrf).header("X-XSRF-TOKEN", updateCsrf.getValue())
                        .contentType("application/json").content(tariffBody(code, "0.25", start, occupiedZone,
                                0L, "Cambio de zona en prueba QA")))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT zone_id FROM parking.tariffs WHERE id=?", UUID.class, scheduledId))
                .isEqualTo(originalZone);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariff_change_history WHERE tariff_id=? AND action='SCHEDULED_UPDATED'", Integer.class, scheduledId))
                .isZero();
    }

    @Test
    void scheduledTariffCannotBeEndedThroughCurrentTariffEndpoint() throws Exception {
        UUID zoneId = insertTestZone();
        String code = "QA-END-SCHEDULED-" + UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime starts = java.time.OffsetDateTime.now(java.time.ZoneId.of("America/Guayaquil"))
                .plusDays(3).withSecond(0).withNano(0);
        Cookie createCsrf = csrfCookie();
        MvcResult created = mvc.perform(post("/api/v1/admin/parking/tariffs").secure(true).cookie(sessionCookie, createCsrf)
                        .header("X-XSRF-TOKEN", createCsrf.getValue()).contentType("application/json")
                        .content(tariffBody(code, "0.25", starts, zoneId)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode scheduled = json.readTree(created.getResponse().getContentAsString());
        UUID id = UUID.fromString(scheduled.path("id").asText());
        Cookie endCsrf = csrfCookie();
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/end").secure(true)
                        .cookie(sessionCookie, endCsrf).header("X-XSRF-TOKEN", endCsrf.getValue())
                        .contentType("application/json")
                        .content("{\"effectiveAt\":\"" + starts.plusDays(1) + "\",\"reason\":\"Prueba QA\",\"expectedVersion\":" + scheduled.path("rowVersion").asLong() + "}"))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT active FROM parking.tariffs WHERE id=?", Boolean.class, id))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM parking.tariff_change_history WHERE tariff_id=? AND action='ENDED'", Integer.class, id))
                .isZero();
    }

    private JsonNode effectiveAt(java.time.OffsetDateTime at) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/admin/parking/configuration/effective").secure(true)
                        .cookie(sessionCookie).param("at", at.toString())).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode zoneTariff(JsonNode snapshot, UUID zoneId) {
        for (JsonNode zone : snapshot.path("zones")) if (zoneId.toString().equals(zone.path("zoneId").asText())) return zone.path("tariff");
        throw new AssertionError("Expected QA zone in effective tariff snapshot");
    }

    @Test
    void scheduledTariffWritesRequireAdminSessionAndCsrf() throws Exception {
        String id = UUID.randomUUID().toString();
        String body = "{\"reason\":\"Incorrecta\"}";
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/cancel").secure(true)
                        .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/admin/parking/tariffs/" + id).secure(true)
                        .contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/cancel").secure(true).cookie(sessionCookie)
                        .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/revise-now").secure(true)
                        .contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/end-now").secure(true)
                        .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/parking/tariffs/" + id + "/end-now").secure(true).cookie(sessionCookie)
                        .contentType("application/json").content(body)).andExpect(status().isForbidden());
    }

    private Cookie csrfCookie() throws Exception {
        return cookie(mvc.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isNoContent()).andReturn(), "__Host-SIMERTPI_ADMIN_XSRF");
    }

    private UUID insertTestZone() {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().substring(0, 8);
        jdbc.update("INSERT INTO parking.zones(id,code,name,active) VALUES (?,?,?,TRUE)", id, "QA-SZ-" + suffix, "Zona de tarifa programada QA");
        return id;
    }

    private String tariffBody(String code, String amount, java.time.OffsetDateTime start, UUID zoneId) throws Exception {
        return tariffBody(code, amount, start, zoneId, null, "Alta de prueba respaldada por referencia QA");
    }

    private String tariffBody(String code, String amount, java.time.OffsetDateTime start, UUID zoneId,
            Long expectedVersion, String reason) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", code); body.put("name", "Tarifa programada QA"); body.put("zoneId", zoneId);
        body.put("currency", "USD"); body.put("amount", amount);
        body.put("durationMinutes", 60); body.put("minMinutes", 15); body.put("maxContinuousMinutes", 240);
        body.put("gracePeriodMinutes", 0); body.put("roundingMode", "HALF_UP"); body.put("validFrom", start.toString());
        body.put("normativeReference", "Referencia QA");
        body.put("reason", reason);
        if (expectedVersion != null) body.put("expectedVersion", expectedVersion);
        return json.writeValueAsString(body);
    }

    private String immediateTariffBody(String code, String amount, long expectedVersion, String reason) throws Exception {
        return json.writeValueAsString(java.util.Map.ofEntries(java.util.Map.entry("code", code),
                java.util.Map.entry("name", "Tarifa inmediata QA"), java.util.Map.entry("currency", "USD"),
                java.util.Map.entry("amount", amount), java.util.Map.entry("durationMinutes", 60),
                java.util.Map.entry("minMinutes", 15), java.util.Map.entry("maxContinuousMinutes", 240),
                java.util.Map.entry("gracePeriodMinutes", 0), java.util.Map.entry("roundingMode", "HALF_UP"),
                java.util.Map.entry("normativeReference", "Referencia QA"), java.util.Map.entry("reason", reason),
                java.util.Map.entry("expectedVersion", expectedVersion)));
    }

    private JsonNode record(MvcResult result, UUID id) throws Exception {
        for (JsonNode row : json.readTree(result.getResponse().getContentAsString())) {
            if (id.toString().equals(row.path("id").asText())) return row;
        }
        throw new AssertionError("Expected isolated fixture was not returned");
    }

    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }
}
