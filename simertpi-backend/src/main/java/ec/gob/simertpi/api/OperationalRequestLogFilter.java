package ec.gob.simertpi.api;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** Technical request activity for Render logs. Never reads request bodies or credential headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OperationalRequestLogFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(OperationalRequestLogFilter.class);
    public static final String REASON = "simertpi.log.reason";
    public static final String EXCEPTION = "simertpi.log.exception";
    public static final String ROOT_EXCEPTION = "simertpi.log.rootException";
    public static final String SOURCE = "simertpi.log.source";
    public static final String SQL_STATE = "simertpi.log.sqlState";
    public static final String ACTOR_ID = "simertpi.log.actorId";
    private static final Pattern UUID_SEGMENT = Pattern.compile("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}(?=/|$)");
    private static final Pattern NUMBER_SEGMENT = Pattern.compile("/[0-9]+(?=/|$)");
    private static final Pattern SENSITIVE_PATH_VALUE = Pattern.compile("(?i)(/(?:plate|username|qr|code)/)[^/]+");

    public static void mark(HttpServletRequest request, String reason, Throwable failure) {
        request.setAttribute(REASON, reason);
        if (failure == null) return;
        request.setAttribute(EXCEPTION, failure.getClass().getName());
        Throwable root = failure;
        for (int i = 0; i < 8 && root.getCause() != null && root.getCause() != root; i++) root = root.getCause();
        request.setAttribute(ROOT_EXCEPTION, root.getClass().getName());
        if (root instanceof java.sql.SQLException sql && sql.getSQLState() != null
                && sql.getSQLState().matches("[A-Z0-9]{5}")) request.setAttribute(SQL_STATE, sql.getSQLState());
        if (root.getStackTrace().length > 0) {
            StackTraceElement frame = root.getStackTrace()[0];
            request.setAttribute(SOURCE, frame.getClassName() + "#" + frame.getMethodName());
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            record(request, response, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        int status = response.getStatus();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String event = event(method, path, status);
        if (event == null) return;

        boolean success = status < 400;
        String result = success ? "SUCCESS" : (status == 401 || status == 403 ? "REJECTED" : "FAILURE");
        Object markedReason = request.getAttribute(REASON);
        String reason = markedReason instanceof String value ? value : defaultReason(event, status, success);
        Object actor = request.getAttribute(ACTOR_ID);
        if (!(actor instanceof UUID) && request.getAttribute("admin.identity") instanceof AdminAuthenticationService.Identity identity)
            actor = identity.userId();
        String actorField = actor instanceof UUID id && needsActorId(event) ? " actorId=" + id : "";
        String correlationId = MDC.get("correlationId");
        String safeCorrelation = correlationId == null ? "-" : correlationId;
        String exception = safeAttribute(request, EXCEPTION);
        String rootException = safeAttribute(request, ROOT_EXCEPTION);
        String source = safeAttribute(request, SOURCE);
        String sqlState = safeAttribute(request, SQL_STATE);
        String line = "event=" + event + " timestamp=" + Instant.now()
                + " method=" + method + " route=" + normalizeRoute(path)
                + " status=" + status + " result=" + result + " reason=" + reason
                + " correlationId=" + safeCorrelation + " durationMs=" + durationMs
                + actorField + " exceptionType=" + exception + " rootCauseType=" + rootException
                + " sqlState=" + sqlState + " source=" + source;
        if (status >= 500) LOG.error(line);
        else if (status >= 400) LOG.warn(line);
        else LOG.info(line);
    }

    private static String event(String method, String path, int status) {
        if ("GET".equals(method) && "/api/v1/admin/auth/csrf".equals(path) && status == 204) return "ADMIN_CSRF_ISSUED";
        if ("POST".equals(method) && "/api/v1/admin/auth/login".equals(path)) return "ADMIN_LOGIN";
        if ("POST".equals(method) && "/api/v1/admin/parking/tariffs".equals(path)) return "ADMIN_TARIFF_CREATE";
        if ("POST".equals(method) && path.matches("/api/v1/admin/parking/tariffs/[^/]+/end")) return "ADMIN_TARIFF_END";
        if ("PUT".equals(method) && path.matches("/api/v1/admin/parking/tariffs/[^/]+")) return "ADMIN_TARIFF_SCHEDULE_UPDATE";
        if ("POST".equals(method) && path.matches("/api/v1/admin/parking/tariffs/[^/]+/cancel")) return "ADMIN_TARIFF_SCHEDULE_CANCEL";
        if ("POST".equals(method) && "/api/v1/admin/parking/billing-day-calendars".equals(path)) return "ADMIN_BILLING_CALENDAR_CREATE";
        if ("POST".equals(method) && path.matches("/api/v1/admin/parking/billing-day-calendars/[^/]+/end")) return "ADMIN_BILLING_CALENDAR_END";
        if (path.startsWith("/api/v1/admin/") && (status == 401 || status == 403))
            return status == 401 ? "ADMIN_SESSION_REJECTED" : "ADMIN_ACCESS_REJECTED";
        if (status == 400 || status == 401 || status == 403 || status == 409 || status == 500)
            return "HTTP_REQUEST_FAILURE";
        return null;
    }

    public static String normalizeRoute(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = UUID_SEGMENT.matcher(path).replaceAll("/{id}");
        normalized = NUMBER_SEGMENT.matcher(normalized).replaceAll("/{id}");
        normalized = SENSITIVE_PATH_VALUE.matcher(normalized).replaceAll("$1{value}");
        return normalized.length() > 180 ? normalized.substring(0, 180) : normalized;
    }

    private static String defaultReason(String event, int status, boolean success) {
        if ("ADMIN_CSRF_ISSUED".equals(event)) return "CSRF_TOKEN_ISSUED";
        if ("ADMIN_LOGIN".equals(event)) return success ? "LOGIN_SUCCESS" : "LOGIN_REJECTED";
        if (success) return "COMPLETED";
        return switch (status) {
            case 400 -> "VALIDATION_ERROR";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "ACCESS_DENIED";
            case 409 -> "BUSINESS_CONFLICT";
            case 500 -> "UNEXPECTED_ERROR";
            default -> "REQUEST_FAILURE";
        };
    }

    private static boolean needsActorId(String event) {
        return event.startsWith("ADMIN_") && !"ADMIN_CSRF_ISSUED".equals(event);
    }

    private static String safeAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        if (!(value instanceof String text)) return "-";
        return text.replaceAll("[^A-Za-z0-9_.$#-]", "_").substring(0, Math.min(text.length(), 180));
    }
}
