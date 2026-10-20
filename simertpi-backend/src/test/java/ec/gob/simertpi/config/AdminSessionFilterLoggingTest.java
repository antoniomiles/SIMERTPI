package ec.gob.simertpi.config;

import ec.gob.simertpi.api.OperationalRequestLogFilter;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.application.identity.admin.AdminSessionExpiredAuthenticationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSessionFilterLoggingTest {
    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void keepsExpiredSessionAs401AndMarksOnlySafeReason() throws Exception {
        AdminAuthenticationService auth = mock(AdminAuthenticationService.class);
        when(auth.authenticate("session-fixture-not-a-secret"))
                .thenThrow(new AdminSessionExpiredAuthenticationException());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/payments");
        request.setRequestURI("/api/v1/admin/payments");
        request.setCookies(new jakarta.servlet.http.Cookie(AdminAuthenticationService.COOKIE, "session-fixture-not-a-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AdminSessionFilter(auth, "https://admin.example.invalid")
                .doFilter(request, response, (req, res) -> { throw new AssertionError("expired session must be rejected"); });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute(OperationalRequestLogFilter.REASON)).isEqualTo("SESSION_EXPIRED");
        assertThat(response.getContentAsString()).doesNotContain("session-fixture-not-a-secret");
    }

    @Test
    void distinguishesMissingAndInvalidSessionWithoutEchoingCookie() throws Exception {
        AdminAuthenticationService auth = mock(AdminAuthenticationService.class);
        when(auth.authenticate(null))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("missing"));
        when(auth.authenticate("invalid-session-fixture"))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("invalid"));
        MockHttpServletRequest missing = new MockHttpServletRequest("GET", "/api/v1/admin/payments");
        missing.setRequestURI("/api/v1/admin/payments");
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        new AdminSessionFilter(auth, "").doFilter(missing, missingResponse, (req, res) -> { });

        MockHttpServletRequest invalid = new MockHttpServletRequest("GET", "/api/v1/admin/payments");
        invalid.setRequestURI("/api/v1/admin/payments");
        invalid.setCookies(new jakarta.servlet.http.Cookie(AdminAuthenticationService.COOKIE, "invalid-session-fixture"));
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        new AdminSessionFilter(auth, "").doFilter(invalid, invalidResponse, (req, res) -> { });

        assertThat(missingResponse.getStatus()).isEqualTo(401);
        assertThat(missing.getAttribute(OperationalRequestLogFilter.REASON)).isEqualTo("SESSION_MISSING");
        assertThat(invalidResponse.getStatus()).isEqualTo(401);
        assertThat(invalid.getAttribute(OperationalRequestLogFilter.REASON)).isEqualTo("SESSION_INVALID");
        assertThat(invalidResponse.getContentAsString()).doesNotContain("invalid-session-fixture");
    }
}
