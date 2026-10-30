package ec.gob.simertpi.application.identity.admin.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPermissionPolicyTest {
    private final AdminPermissionPolicy policy = new AdminPermissionPolicy();

    @Test
    void secretaryCanReadDashboardAndReportsOnly() {
        var secretary = authentication("PERM:dashboard.view", "PERM:reports.view", "ADMIN_PORTAL");
        assertThat(check("GET", "/api/v1/admin/auth/me", secretary)).isTrue();
        assertThat(check("GET", "/api/v1/admin/reports", secretary)).isTrue();
        assertThat(check("GET", "/api/v1/admin/parking/tariffs", secretary)).isFalse();
        assertThat(check("GET", "/api/v1/admin/access/users", secretary)).isFalse();
        assertThat(check("POST", "/api/v1/admin/parking/zones", secretary)).isFalse();
    }

    @Test
    void customRoleNeedsExactPermissionAndVerb() {
        var role = authentication("ADMIN_PORTAL", "PERM:zones.view");
        assertThat(check("GET", "/api/v1/admin/parking/zones", role)).isTrue();
        assertThat(check("POST", "/api/v1/admin/parking/zones", role)).isFalse();
        assertThat(check("GET", "/api/v1/admin/parking/zones/unknown/unmapped", role)).isFalse();
    }

    @Test
    void legacyAdministratorRetainsOperationalAccessButCannotManagePrivilegeCatalog() {
        var admin = authentication("SIMERTPI_ADMIN", "ADMIN_PORTAL");
        assertThat(check("POST", "/api/v1/admin/parking/tariffs", admin)).isTrue();
        assertThat(check("GET", "/api/v1/admin/access/roles", admin)).isFalse();
        assertThat(check("PUT", "/api/v1/admin/access/roles/SECRETARIA", admin)).isFalse();
    }

    @Test
    void superadministratorCanUseKnownAdministrativeRoutesButUnknownRoutesRemainDenied() {
        var superadmin = authentication("IT_ADMIN", "ADMIN_PORTAL");
        assertThat(check("POST", "/api/v1/admin/access/roles", superadmin)).isTrue();
        assertThat(check("DELETE", "/api/v1/admin/parking/zones/1", superadmin)).isFalse();
        assertThat(check("GET", "/api/v1/admin/not-registered", superadmin)).isFalse();
    }

    @Test
    void unauthenticatedAndUnsupportedMethodsAreDenied() {
        var anonymous = UsernamePasswordAuthenticationToken.unauthenticated("guest", "");
        assertThat(check("GET", "/api/v1/admin/reports", anonymous)).isFalse();
        var reportReader = authentication("PERM:reports.view", "ADMIN_PORTAL");
        assertThat(check("DELETE", "/api/v1/admin/reports", reportReader)).isFalse();
    }

    @Test
    void supportedPermissionCodesAreUniqueAndStable() {
        var codes = AdminPermissionPolicy.supportedPermissionCodes();
        assertThat(codes).contains("dashboard.view", "reports.view", "tariffs.view", "tariffs.create",
                "zones.view", "users.view");
        assertThat(codes).doesNotContain("roles.view"); // role/permission administration is a protected IT_ADMIN capability.
        assertThat(codes).doesNotHaveDuplicates();
    }

    private boolean check(String method, String path, org.springframework.security.core.Authentication authentication) {
        var request = new MockHttpServletRequest(method, path);
        return policy.check(() -> authentication, new RequestAuthorizationContext(request)).isGranted();
    }

    private static UsernamePasswordAuthenticationToken authentication(String... authorities) {
        return UsernamePasswordAuthenticationToken.authenticated("test", null,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }
}
