package ec.gob.simertpi.application.identity.smtp;

/** Narrow Microsoft OAuth port. Token material must remain server-side. */
public interface MicrosoftOAuthClient {
    TokenResponse exchange(String code, String verifier, String redirectUri);

    TokenResponse refresh(String refreshToken);

    AuthorizedIdentity validateIdentity(String idToken, String expectedNonce);

    record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, String idToken) {
        @Override public String toString() {
            return "MicrosoftTokenResponse[accessToken=redacted,refreshToken=redacted,idToken=redacted,expiresInSeconds="
                    + expiresInSeconds + "]";
        }
    }

    record AuthorizedIdentity(String email, String subject) {
        @Override public String toString() {
            return "MicrosoftAuthorizedIdentity[email=redacted,subject=redacted]";
        }
    }
}
