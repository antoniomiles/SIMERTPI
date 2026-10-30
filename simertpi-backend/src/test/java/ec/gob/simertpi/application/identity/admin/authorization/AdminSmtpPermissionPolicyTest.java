package ec.gob.simertpi.application.identity.admin.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSmtpPermissionPolicyTest {
    private final AdminPermissionPolicy policy = new AdminPermissionPolicy();

    @Test void smtpConfigurationIsOnlyAvailableToItAdmin() {
        assertThat(allowed("IT_ADMIN", HttpMethod.GET, "/api/v1/admin/system/email")).isTrue();
        assertThat(allowed("SIMERTPI_ADMIN", HttpMethod.GET, "/api/v1/admin/system/email")).isFalse();
        assertThat(allowed("IT_ADMIN", HttpMethod.PUT, "/api/v1/admin/system/email/draft")).isTrue();
        assertThat(allowed("SIMERTPI_ADMIN", HttpMethod.POST, "/api/v1/admin/system/email/activate")).isFalse();
        for (String action : List.of("test-connection", "test-email", "activate", "disable")) {
            assertThat(allowed("IT_ADMIN", HttpMethod.POST, "/api/v1/admin/system/email/" + action)).isTrue();
            assertThat(allowed("SIMERTPI_ADMIN", HttpMethod.POST, "/api/v1/admin/system/email/" + action)).isFalse();
        }
        assertThat(allowed("IT_ADMIN", HttpMethod.DELETE, "/api/v1/admin/system/email")).isFalse();
    }

    private boolean allowed(String role, HttpMethod method, String path) {
        var auth = UsernamePasswordAuthenticationToken.authenticated("test", null,
                List.of(new SimpleGrantedAuthority(role), new SimpleGrantedAuthority("ADMIN_PORTAL")));
        return policy.check(() -> auth, new RequestAuthorizationContext(new MockHttpServletRequest(method.name(), path))).isGranted();
    }
}
