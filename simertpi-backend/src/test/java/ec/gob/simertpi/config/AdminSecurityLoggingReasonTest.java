package ec.gob.simertpi.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSecurityLoggingReasonTest {
    @Test
    void distinguishesMissingInvalidCsrfAndAuthorizationDenial() {
        assertThat(AdminSecurityConfiguration.accessDeniedReason(new MissingCsrfTokenException("missing")))
                .isEqualTo("CSRF_MISSING");
        assertThat(AdminSecurityConfiguration.accessDeniedReason(new InvalidCsrfTokenException(
                new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected"), "actual")))
                .isEqualTo("CSRF_INVALID");
        assertThat(AdminSecurityConfiguration.accessDeniedReason(new AccessDeniedException("denied")))
                .isEqualTo("ACCESS_DENIED");
    }
}
