package ec.gob.simertpi.application.identity.admin;

import org.springframework.security.authentication.BadCredentialsException;

/** Internal classification only; the HTTP response remains the same generic 401. */
public final class AdminSessionExpiredAuthenticationException extends BadCredentialsException {
    public AdminSessionExpiredAuthenticationException() {
        super("Administrative session rejected");
    }
}
