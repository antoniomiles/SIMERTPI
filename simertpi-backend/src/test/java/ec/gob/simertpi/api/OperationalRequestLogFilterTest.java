package ec.gob.simertpi.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.read.ListAppender;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalRequestLogFilterTest {
    private static final String SESSION = "session-cookie-secret-fixture";
    private static final String CSRF = "csrf-token-secret-fixture";
    private static final String PASSWORD = "password-secret-fixture";

    @Test
    void logsAdminRejectionWithNormalizedRouteAndNoRequestSecrets() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationalRequestLogFilter.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = appender(logger);
        UUID actorId = UUID.randomUUID();
        MockHttpServletRequest request = request("POST", "/api/v1/admin/parking/tariffs/11111111-1111-4111-8111-111111111111/end");
        request.setQueryString("password=" + PASSWORD);
        request.setContent(PASSWORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setCookies(new jakarta.servlet.http.Cookie("__Host-SIMERTPI_ADMIN_SESSION", SESSION));
        request.addHeader("X-XSRF-TOKEN", CSRF);
        request.setAttribute("admin.identity", new AdminAuthenticationService.Identity(actorId, "not-logged", List.of("SIMERTPI_ADMIN"), true, null, null));
        OperationalRequestLogFilter.mark(request, "CSRF_INVALID",
                new IllegalStateException(PASSWORD, new java.sql.SQLException(PASSWORD, "XX123")));
        MDC.put("correlationId", "tariff-correlation-1");
        try {
            new OperationalRequestLogFilter().doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> ((MockHttpServletResponse) res).setStatus(403));
        } finally {
            MDC.remove("correlationId");
            logger.detachAppender(appender);
        }

        String line = appender.list.getFirst().getFormattedMessage();
        assertThat(line).contains("event=ADMIN_TARIFF_END", "method=POST", "route=/api/v1/admin/parking/tariffs/{id}/end",
                "status=403", "result=REJECTED", "reason=CSRF_INVALID", "correlationId=tariff-correlation-1", "actorId=" + actorId);
        assertThat(line).contains("exceptionType=java.lang.IllegalStateException", "rootCauseType=java.sql.SQLException", "sqlState=XX123")
                .doesNotContain(SESSION, CSRF, PASSWORD, "not-logged", "password=");
    }

    @Test
    void categorizesValidationAuthenticationConflictAndUnexpectedStatuses() throws Exception {
        assertEvent(400, "VALIDATION_ERROR");
        assertEvent(401, "AUTHENTICATION_REQUIRED");
        assertEvent(409, "BUSINESS_CONFLICT");
        assertEvent(500, "UNEXPECTED_ERROR");
    }

    @Test
    void recordsCsrfIssueButSkipsHealthAndOrdinarySuccessfulReads() throws Exception {
        assertEvent("GET", "/api/v1/admin/auth/csrf", 204, "ADMIN_CSRF_ISSUED", "CSRF_TOKEN_ISSUED");
        assertNoEvent("GET", "/actuator/health/readiness", 200);
        assertNoEvent("GET", "/api/v1/admin/parking/tariffs", 200);
    }

    @Test
    void normalizesPersonalAndOpaquePathValuesBeforeLogging() {
        assertThat(OperationalRequestLogFilter.normalizeRoute("/api/v1/vehicles/plate/AB123CD"))
                .isEqualTo("/api/v1/vehicles/plate/{value}");
        assertThat(OperationalRequestLogFilter.normalizeRoute("/api/v1/identity/users/username/admin.test"))
                .isEqualTo("/api/v1/identity/users/username/{value}");
        assertThat(OperationalRequestLogFilter.normalizeRoute("/api/v1/parking/spaces/qr/opaque-code"))
                .isEqualTo("/api/v1/parking/spaces/qr/{value}");
    }

    @Test
    void logsSuccessfulLoginAndAdministrativeConfigurationChanges() throws Exception {
        assertAdminEvent("POST", "/api/v1/admin/auth/login", 200, "ADMIN_LOGIN", "LOGIN_SUCCESS");
        assertEvent("POST", "/api/v1/admin/auth/login", 401, "ADMIN_LOGIN", "LOGIN_REJECTED");
        assertAdminEvent("POST", "/api/v1/admin/parking/tariffs", 201, "ADMIN_TARIFF_CREATE", "COMPLETED");
        assertAdminEvent("POST", "/api/v1/admin/parking/tariffs/11111111-1111-4111-8111-111111111111/end",
                200, "ADMIN_TARIFF_END", "COMPLETED");
        assertAdminEvent("POST", "/api/v1/admin/parking/billing-day-calendars", 201,
                "ADMIN_BILLING_CALENDAR_CREATE", "COMPLETED");
        assertAdminEvent("POST", "/api/v1/admin/parking/billing-day-calendars/11111111-1111-4111-8111-111111111111/end",
                200, "ADMIN_BILLING_CALENDAR_END", "COMPLETED");
    }

    private void assertEvent(int status, String reason) throws Exception {
        assertEvent("POST", "/api/v1/citizen/endpoint", status, "HTTP_REQUEST_FAILURE", reason);
    }

    private void assertEvent(String method, String path, int status, String event, String reason) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationalRequestLogFilter.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = appender(logger);
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        try {
            new OperationalRequestLogFilter().doFilter(request, response, (req, res) -> { });
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("event=" + event, "status=" + status, "reason=" + reason);
    }

    private void assertNoEvent(String method, String path, int status) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationalRequestLogFilter.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = appender(logger);
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        try {
            new OperationalRequestLogFilter().doFilter(request, response, (req, res) -> { });
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).isEmpty();
    }

    private void assertAdminEvent(String method, String path, int status, String event, String reason) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationalRequestLogFilter.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = appender(logger);
        UUID actorId = UUID.randomUUID();
        MockHttpServletRequest request = request(method, path);
        request.setAttribute(OperationalRequestLogFilter.ACTOR_ID, actorId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        try {
            new OperationalRequestLogFilter().doFilter(request, response, (req, res) -> { });
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("event=" + event, "status=" + status, "reason=" + reason, "actorId=" + actorId);
    }

    private static ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender(Logger logger) {
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
