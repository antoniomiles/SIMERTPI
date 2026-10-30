package ec.gob.simertpi.application.identity.smtp;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "simertpi.smtp.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "simertpi.smtp.microsoft.client-id=00000000-0000-0000-0000-000000000001",
        "simertpi.smtp.microsoft.client-secret=test-client-secret-not-real",
        "simertpi.smtp.microsoft.redirect-uri=https://simertpi-backend-dev.onrender.com/api/v1/admin/system/email/oauth/callback",
        "simertpi.smtp.microsoft.authorized-sender=simertpi@outlook.com",
        "simertpi.smtp.test-email-enabled=false"
})
class MicrosoftOAuthSmtpPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String SENDER = "simertpi@outlook.com";
    private static final String REDIRECT = "https://simertpi-backend-dev.onrender.com/api/v1/admin/system/email/oauth/callback";
    @Autowired MicrosoftOAuthSmtpService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired SmtpCredentialCipher cipher;
    @MockitoBean MicrosoftOAuthClient oauth;
    private String username;
    private String session;

    @BeforeEach void prepareAdminSession() {
        jdbc.update("DELETE FROM identity.admin_smtp_oauth_connection");
        jdbc.update("DELETE FROM identity.admin_smtp_oauth_attempt");
        jdbc.update("DELETE FROM identity.admin_smtp_oauth_test_attempt");
        username = "oauth-admin-" + UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,true)",
                userId, username, username + "@example.invalid", "test-hash", "OAuth", "Fixture");
        jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='IT_ADMIN'", userId);
        byte[] opaque = new byte[32]; new java.security.SecureRandom().nextBytes(opaque);
        session = Base64.getUrlEncoder().withoutPadding().encodeToString(opaque);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO identity.admin_web_sessions(id,user_id,token_hash,security_stamp,created_at,absolute_expires_at,idle_expires_at) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), userId, AdminAuthenticationService.hash(session), "test-stamp", now, now.plusHours(1), now.plusMinutes(10));
        reset(oauth);
    }

    @Test void authorizationStateIsHashedPkceVerifierIsEncryptedAndCallbackIsOneUse() throws Exception {
        var started = service.start(username, session);
        String state = query(started.authorizationUrl(), "state");
        String challenge = query(started.authorizationUrl(), "code_challenge");
        String expectedNonce = query(started.authorizationUrl(), "nonce");
        String verifierHash = sha256(state);
        var stored = jdbc.queryForMap("SELECT state_hash,nonce,verifier_nonce,verifier_ciphertext,admin_session_hash,expected_sender FROM identity.admin_smtp_oauth_attempt WHERE state_hash=?", verifierHash);
        assertThat(stored.get("state_hash")).isEqualTo(verifierHash).isNotEqualTo(state);
        assertThat(stored.get("nonce")).isEqualTo(expectedNonce);
        assertThat(stored.get("admin_session_hash")).isEqualTo(AdminAuthenticationService.hash(session));
        assertThat(stored.get("expected_sender")).isEqualTo(SENDER);
        byte[] verifierCiphertext = (byte[]) stored.get("verifier_ciphertext");
        assertThat(started.authorizationUrl()).doesNotContain("code_verifier");
        assertThat(verifierCiphertext).isNotEmpty();
        byte[] verifierNonce = (byte[]) stored.get("verifier_nonce");
        char[] verifier = cipher.decrypt(verifierNonce, verifierCiphertext);
        String clearVerifier = new String(verifier);
        try {
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(clearVerifier.getBytes(StandardCharsets.US_ASCII)));
            assertThat(challenge).isEqualTo(computed);
            assertThat(verifierCiphertext).isNotEqualTo(clearVerifier.getBytes(StandardCharsets.UTF_8));
        } finally { java.util.Arrays.fill(verifier, '\0'); }

        when(oauth.exchange(eq("authorization-code-fixture"), anyString(), eq(REDIRECT)))
                .thenReturn(new MicrosoftOAuthClient.TokenResponse("access-token-fixture", "refresh-token-fixture", 3600, "id-token-fixture"));
        when(oauth.validateIdentity(eq("id-token-fixture"), eq(expectedNonce)))
                .thenReturn(new MicrosoftOAuthClient.AuthorizedIdentity(SENDER, "microsoft-subject-fixture"));
        assertThat(service.complete(state, "authorization-code-fixture", null)).isEqualTo("connected");
        assertThat(service.complete(state, "authorization-code-fixture", null)).isEqualTo("failed");
        verify(oauth, times(1)).exchange(eq("authorization-code-fixture"), anyString(), eq(REDIRECT));

        var status = service.status();
        assertThat(status.state()).isEqualTo("CONNECTED");
        assertThat(status.authorizedAccount()).isEqualTo(SENDER);
        assertThat(status.toString()).doesNotContain("access-token-fixture", "refresh-token-fixture", "id-token-fixture");
        var persisted = jdbc.queryForMap("SELECT access_token_ciphertext,refresh_token_ciphertext FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1");
        assertThat((byte[]) persisted.get("access_token_ciphertext")).isNotEqualTo("access-token-fixture".getBytes(StandardCharsets.UTF_8));
        assertThat((byte[]) persisted.get("refresh_token_ciphertext")).isNotEqualTo("refresh-token-fixture".getBytes(StandardCharsets.UTF_8));
    }

    @Test void invalidStateExpiredStateAndRevokedAdminSessionAreRejectedWithoutTokenExchange() {
        assertThat(service.complete("not-a-state", "code", null)).isEqualTo("failed");
        var expiredStart = service.start(username, session);
        String expiredState = query(expiredStart.authorizationUrl(), "state");
        jdbc.update("UPDATE identity.admin_smtp_oauth_attempt SET created_at=now()-interval '10 seconds',expires_at=now()-interval '1 second' WHERE state_hash=?", sha256(expiredState));
        assertThat(service.complete(expiredState, "code", null)).isEqualTo("failed");

        var revokedStart = service.start(username, session);
        String revokedState = query(revokedStart.authorizationUrl(), "state");
        jdbc.update("UPDATE identity.admin_web_sessions SET revoked_at=now() WHERE token_hash=?", AdminAuthenticationService.hash(session));
        assertThat(service.complete(revokedState, "code", null)).isEqualTo("failed");
        verifyNoInteractions(oauth);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_oauth_connection", Integer.class)).isZero();
    }

    @Test void providerAccountMismatchConsumesCallbackAndDoesNotPersistTokens() {
        var started = service.start(username, session);
        String state = query(started.authorizationUrl(), "state");
        String nonce = query(started.authorizationUrl(), "nonce");
        when(oauth.exchange(eq("code"), anyString(), eq(REDIRECT)))
                .thenReturn(new MicrosoftOAuthClient.TokenResponse("access-secret", "refresh-secret", 3600, "id-token"));
        when(oauth.validateIdentity("id-token", nonce))
                .thenReturn(new MicrosoftOAuthClient.AuthorizedIdentity("another-account@example.invalid", "other-subject"));
        assertThat(service.complete(state, "code", null)).isEqualTo("failed");
        assertThat(service.complete(state, "code", null)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_oauth_connection", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM identity.admin_smtp_oauth_attempt WHERE state_hash=?", Boolean.class, sha256(state))).isTrue();
        verify(oauth, times(1)).exchange(eq("code"), anyString(), eq(REDIRECT));
    }

    @Test void tokenExchangeProviderErrorConsumesTheOneUseState() {
        var started = service.start(username, session);
        String state = query(started.authorizationUrl(), "state");
        assertThat(service.complete(state, null, "access_denied")).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM identity.admin_smtp_oauth_attempt WHERE state_hash=?", Boolean.class, sha256(state))).isTrue();
        verifyNoInteractions(oauth);
    }

    @Test void expiredAccessTokenRefreshPersistsRotatedRefreshTokenAtomically() {
        saveActiveTokens("old-access", "old-refresh");
        when(oauth.refresh("old-refresh"))
                .thenReturn(new MicrosoftOAuthClient.TokenResponse("new-access", "rotated-refresh", 3600, null));
        var credential = service.activeCredential();
        try { assertThat(new String(credential.accessToken())).isEqualTo("new-access"); }
        finally { java.util.Arrays.fill(credential.accessToken(), '\0'); }
        var row = jdbc.queryForMap("SELECT access_token_nonce,access_token_ciphertext,refresh_token_nonce,refresh_token_ciphertext,last_refreshed_at,state FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1");
        char[] access = cipher.decrypt((byte[]) row.get("access_token_nonce"), (byte[]) row.get("access_token_ciphertext"));
        char[] refresh = cipher.decrypt((byte[]) row.get("refresh_token_nonce"), (byte[]) row.get("refresh_token_ciphertext"));
        try {
            assertThat(new String(access)).isEqualTo("new-access");
            assertThat(new String(refresh)).isEqualTo("rotated-refresh");
        } finally { java.util.Arrays.fill(access, '\0'); java.util.Arrays.fill(refresh, '\0'); }
        assertThat(row.get("last_refreshed_at")).isNotNull();
        assertThat(row.get("state")).isEqualTo("ACTIVE");
    }

    @Test void missingRotatedRefreshTokenPreservesCurrentRefreshTokenAndInvalidGrantRequiresReauthorization() {
        saveActiveTokens("old-access", "stable-refresh");
        when(oauth.refresh("stable-refresh"))
                .thenReturn(new MicrosoftOAuthClient.TokenResponse("new-access", null, 3600, null));
        var credential = service.activeCredential();
        java.util.Arrays.fill(credential.accessToken(), '\0');
        var row = jdbc.queryForMap("SELECT refresh_token_nonce,refresh_token_ciphertext FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1");
        char[] refresh = cipher.decrypt((byte[]) row.get("refresh_token_nonce"), (byte[]) row.get("refresh_token_ciphertext"));
        try { assertThat(new String(refresh)).isEqualTo("stable-refresh"); }
        finally { java.util.Arrays.fill(refresh, '\0'); }

        jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET access_expires_at=now()-interval '1 second'");
        when(oauth.refresh("stable-refresh")).thenThrow(new MicrosoftOAuthClientException("INVALID_GRANT"));
        org.assertj.core.api.Assertions.assertThatThrownBy(service::activeCredential)
                .isInstanceOf(MicrosoftOAuthSmtpService.OAuthSmtpException.class)
                .hasMessage("La autorización Microsoft no está disponible para el envío")
                .hasMessageNotContaining("stable-refresh");
        assertThat(jdbc.queryForObject("SELECT state FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1", String.class)).isEqualTo("REAUTH_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1", String.class)).isEqualTo("INVALID_GRANT");
    }

    private void saveActiveTokens(String accessToken, String refreshToken) {
        SmtpCredentialCipher.Encrypted access = cipher.encrypt(accessToken.toCharArray());
        SmtpCredentialCipher.Encrypted refresh = cipher.encrypt(refreshToken.toCharArray());
        jdbc.update("""
                INSERT INTO identity.admin_smtp_oauth_connection
                 (singleton_id,state,sender_email,microsoft_subject,access_token_nonce,access_token_ciphertext,
                  refresh_token_nonce,refresh_token_ciphertext,access_expires_at,connected_at,last_refreshed_at,
                  last_error_code,test_passed_at,updated_by,updated_at,row_version)
                VALUES (1,'ACTIVE',?,?,?,?,?,?,now()-interval '1 second',now()-interval '1 hour',NULL,NULL,now(),?,now(),0)
                """, SENDER, "microsoft-subject-fixture", access.nonce(), access.ciphertext(),
                refresh.nonce(), refresh.ciphertext(), username);
    }

    private static String query(String url, String key) {
        String query = java.net.URI.create(url).getRawQuery();
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key))
                return java.net.URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError("Missing OAuth parameter: " + key);
    }

    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
