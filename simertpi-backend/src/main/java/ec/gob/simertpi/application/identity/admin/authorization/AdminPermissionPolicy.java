package ec.gob.simertpi.application.identity.admin.authorization;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.function.Supplier;

/** Central, fail-closed policy for administrative HTTP endpoints. */
public final class AdminPermissionPolicy implements AuthorizationManager<RequestAuthorizationContext> {
    private static final String ADMIN = "SIMERTPI_ADMIN";
    private static final String SUPERADMIN = "IT_ADMIN";
    private static final String PORTAL = "ADMIN_PORTAL";
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private record Rule(HttpMethod method, String pattern, String permission, boolean superadminOnly,
                        boolean portalIdentityOnly) { }

    private static Rule permission(HttpMethod method, String path, String permission) {
        return new Rule(method, path, permission, false, false);
    }
    private static Rule superadmin(HttpMethod method, String path) {
        return new Rule(method, path, null, true, false);
    }
    private static Rule portal(HttpMethod method, String path) {
        return new Rule(method, path, null, false, true);
    }

    private static final List<Rule> RULES = List.of(
            portal(HttpMethod.GET, "/api/v1/admin/auth/me"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/reentry-configuration", "schedules.view"),
            permission(HttpMethod.PUT, "/api/v1/admin/parking/reentry-configuration", "schedules.update"),

            permission(HttpMethod.GET, "/api/v1/admin/audit", "audit.view"),
            superadmin(HttpMethod.GET, "/api/v1/admin/configuration-history"),
            permission(HttpMethod.GET, "/api/v1/admin/access/users", "users.view"),
            superadmin(HttpMethod.GET, "/api/v1/admin/access/roles"),
            superadmin(HttpMethod.GET, "/api/v1/admin/access/roles/*"),
            superadmin(HttpMethod.GET, "/api/v1/admin/access/roles/*/history"),
            superadmin(HttpMethod.GET, "/api/v1/admin/access/permissions"),
            superadmin(HttpMethod.GET, "/api/v1/admin/access/permission-catalog"),
            superadmin(HttpMethod.GET, "/api/v1/admin/system/email"),
            superadmin(HttpMethod.PUT, "/api/v1/admin/system/email/draft"),
            superadmin(HttpMethod.POST, "/api/v1/admin/system/email/test-connection"),
            superadmin(HttpMethod.POST, "/api/v1/admin/system/email/test-email"),
            superadmin(HttpMethod.POST, "/api/v1/admin/system/email/activate"),
            superadmin(HttpMethod.POST, "/api/v1/admin/system/email/disable"),
            permission(HttpMethod.POST, "/api/v1/admin/access/users", "users.create"),
            superadmin(HttpMethod.POST, "/api/v1/admin/access/roles"),
            superadmin(HttpMethod.POST, "/api/v1/admin/access/users/*/activation"),
            superadmin(HttpMethod.PUT, "/api/v1/admin/access/roles/*"),
            superadmin(HttpMethod.PUT, "/api/v1/admin/access/roles/*/status"),
            permission(HttpMethod.PUT, "/api/v1/admin/access/users/*/roles", "users.roles.assign"),
            permission(HttpMethod.PUT, "/api/v1/admin/access/users/*/status", "users.update"),
            permission(HttpMethod.PUT, "/api/v1/admin/access/users/*", "users.update"),

            permission(HttpMethod.GET, "/api/v1/admin/inspectors", "inspectors.view"),
            permission(HttpMethod.POST, "/api/v1/admin/inspectors", "inspectors.manage"),
            permission(HttpMethod.POST, "/api/v1/admin/inspectors/*/activation", "users.invitations"),

            permission(HttpMethod.GET, "/api/v1/admin/payments", "payments.view"),
            permission(HttpMethod.GET, "/api/v1/admin/reports", "reports.view"),
            permission(HttpMethod.GET, "/api/v1/admin/dashboard", "dashboard.view"),
            permission(HttpMethod.GET, "/api/v1/admin/inspections", "inspections.view"),
            permission(HttpMethod.GET, "/api/v1/admin/inspections/*", "inspections.view"),
            permission(HttpMethod.GET, "/api/v1/admin/violations", "violations.view"),
            permission(HttpMethod.GET, "/api/v1/admin/violations/*", "violations.view"),
            permission(HttpMethod.GET, "/api/v1/admin/violations/*/evidence", "violations.view"),
            permission(HttpMethod.GET, "/api/v1/admin/evidence/*/content", "violations.view"),

            permission(HttpMethod.GET, "/api/v1/admin/parking/zones", "zones.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/catalog/zones", "zones.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/catalog/streets", "streets.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/catalog/spaces", "spaces.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/catalog/map", "spaces.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/catalog/spaces/nearby", "spaces.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/catalog/map/tiles/**", "spaces.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/zones/*/streets", "streets.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/streets/*/spaces", "spaces.view"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/zones", "zones.create"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/zones/*/streets", "streets.create"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/streets/*/spaces", "spaces.create"),
            permission(HttpMethod.PUT, "/api/v1/admin/parking/zones/*", "zones.update"),
            permission(HttpMethod.PUT, "/api/v1/admin/parking/streets/*", "streets.update"),
            permission(HttpMethod.PUT, "/api/v1/admin/parking/spaces/*", "spaces.update"),

            permission(HttpMethod.GET, "/api/v1/admin/parking/tariffs", "tariffs.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/tariffs/history", "tariffs.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/tariffs/*/history", "tariffs.view"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/tariffs", "tariffs.create"),
            permission(HttpMethod.PUT, "/api/v1/admin/parking/tariffs/*", "tariffs.update"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/tariffs/*/**", "tariffs.update"),

            permission(HttpMethod.GET, "/api/v1/admin/parking/billing-day-calendars", "billing_days.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/billing-day-calendars/*/history", "billing_days.view"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/billing-day-calendars", "billing_days.create"),
            permission(HttpMethod.PUT, "/api/v1/admin/parking/billing-day-calendars/*", "billing_days.update"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/billing-day-calendars/*/**", "billing_days.update"),

            permission(HttpMethod.GET, "/api/v1/admin/parking/configuration/effective", "schedules.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/schedules", "schedules.view"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/schedules/effective", "schedules.view"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/schedules", "schedules.create"),
            permission(HttpMethod.PUT, "/api/v1/admin/parking/schedules/*", "schedules.update"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/schedules/*/**", "schedules.update"),

            permission(HttpMethod.GET, "/api/v1/admin/parking/calendar-exceptions", "holidays.view"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/calendar-exceptions", "holidays.create"),
            permission(HttpMethod.PUT, "/api/v1/admin/parking/calendar-exceptions/*", "holidays.update"),
            permission(HttpMethod.POST, "/api/v1/admin/parking/calendar-exceptions/*/**", "holidays.update"),
            permission(HttpMethod.GET, "/api/v1/admin/parking/sessions", "sessions.view"),
            permission(HttpMethod.POST, "/api/v1/admin/reconciliation/**", "reconciliation.manage")
    );

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        if (current == null || !current.isAuthenticated()) return new AuthorizationDecision(false);
        HttpServletRequest request = context.getRequest();
        String servletPath = request.getServletPath();
        final String path = servletPath == null || servletPath.isBlank()
                ? request.getRequestURI().substring(request.getContextPath().length()) : servletPath;
        HttpMethod method;
        try { method = HttpMethod.valueOf(request.getMethod()); }
        catch (IllegalArgumentException invalidMethod) { return new AuthorizationDecision(false); }
        Rule matched = RULES.stream().filter(rule -> rule.method() == method && PATHS.match(rule.pattern(), path))
                .findFirst().orElse(null);
        if (matched == null) return new AuthorizationDecision(false);
        if (has(current, SUPERADMIN)) return new AuthorizationDecision(true);
        if (matched.superadminOnly()) return new AuthorizationDecision(false);
        if (matched.portalIdentityOnly()) return new AuthorizationDecision(has(current, PORTAL));
        if (has(current, ADMIN)) return new AuthorizationDecision(true);
        return new AuthorizationDecision(has(current, "PERM:" + matched.permission()));
    }

    private static boolean has(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }

    /** Exposed for tests and for startup/catalog consistency checks. */
    public static List<String> supportedPermissionCodes() {
        return RULES.stream().map(Rule::permission).filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }
}
