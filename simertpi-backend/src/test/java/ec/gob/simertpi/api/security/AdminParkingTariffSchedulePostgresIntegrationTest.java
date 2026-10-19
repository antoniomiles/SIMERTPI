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
