package ec.gob.simertpi.application.identity.admin.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MicrosoftOAuthAdminPermissionPolicyTest {
    private final AdminPermissionPolicy policy = new AdminPermissionPolicy();

    @Test void smtpOAuthManagementIsLimitedToSuperadministrator() {
        var superadmin = auth("IT_ADMIN", "ADMIN_PORTAL");
        var admin = auth("SIMERTPI_ADMIN", "ADMIN_PORTAL");
        assertThat(check("GET", "/api/v1/admin/system/email/oauth/status", superadmin)).isTrue();
        assertThat(check("POST", "/api/v1/admin/system/email/oauth/start", superadmin)).isTrue();
        assertThat(check("POST", "/api/v1/admin/system/email/oauth/disconnect", superadmin)).isTrue();
        assertThat(check("GET", "/api/v1/admin/system/email/oauth/status", admin)).isFalse();
        assertThat(check("POST", "/api/v1/admin/system/email/oauth/start", admin)).isFalse();
        assertThat(check("POST", "/api/v1/admin/system/email/oauth/activate", admin)).isFalse();
    }

    @Test void callbackHasNoAuthenticatedPolicyRouteAndOnlyExactGetCanBeHandledAsPublicCallback() {
        var anonymous = UsernamePasswordAuthenticationToken.unauthenticated("guest", "");
        assertThat(check("GET", "/api/v1/admin/system/email/oauth/callback", anonymous)).isFalse();
        var superadmin = auth("IT_ADMIN", "ADMIN_PORTAL");
        assertThat(check("POST", "/api/v1/admin/system/email/oauth/callback", superadmin)).isFalse();
        assertThat(check("GET", "/api/v1/admin/system/email/oauth/callback/extra", superadmin)).isFalse();
    }

    private boolean check(String method, String path, org.springframework.security.core.Authentication authentication) {
        return policy.check(() -> authentication,
                new RequestAuthorizationContext(new MockHttpServletRequest(method, path))).isGranted();
    }

    private static UsernamePasswordAuthenticationToken auth(String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated("fixture", null,
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList());
    }
}
