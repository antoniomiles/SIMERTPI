package ec.gob.simertpi.application.identity.smtp;

import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Server-side Microsoft OAuth connection. Browser receives only a redirect URL and public status. */
@Service
public class MicrosoftOAuthSmtpService {
    private static final String AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private static final String SMTP_HOST = "smtp-mail.outlook.com";
    private static final String SCOPE = "openid profile email offline_access https://outlook.office.com/SMTP.Send";
    private final JdbcTemplate jdbc;
    private final SmtpCredentialCipher cipher;
    private final SmtpTransport transport;
    private final SmtpHostPolicy hostPolicy;
    private final MicrosoftOAuthClient oauth;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final String clientId;
    private final boolean clientSecretPresent;
    private final String redirectUri;
    private final String authorizedSender;
    private final String testRecipient;
    private final boolean testEmailEnabled;

    public MicrosoftOAuthSmtpService(JdbcTemplate jdbc, SmtpCredentialCipher cipher, SmtpTransport transport,
                                     SmtpHostPolicy hostPolicy, MicrosoftOAuthClient oauth, AuditService audit,
                                     @Value("${simertpi.smtp.microsoft.client-id:}") String clientId,
                                     @Value("${simertpi.smtp.microsoft.client-secret:}") String clientSecret,
                                     @Value("${simertpi.smtp.microsoft.redirect-uri:}") String redirectUri,
                                     @Value("${simertpi.smtp.microsoft.authorized-sender:}") String authorizedSender,
                                     @Value("${simertpi.smtp.test-recipient:}") String testRecipient,
                                     @Value("${simertpi.smtp.test-email-enabled:false}") boolean testEmailEnabled) {
        this.jdbc = jdbc; this.cipher = cipher; this.transport = transport; this.hostPolicy = hostPolicy;
        this.oauth = oauth; this.audit = audit;
        this.clientId = trim(clientId); this.clientSecretPresent = clientSecret != null && !clientSecret.isBlank(); this.redirectUri = trim(redirectUri);
        this.authorizedSender = normalizeEmail(authorizedSender);
        this.testRecipient = trim(testRecipient); this.testEmailEnabled = testEmailEnabled;
    }

    @Transactional
    public StartResult start(String actor, String rawAdminSession) {
        ensureOAuthConfigured();
        if (rawAdminSession == null || !AdminAuthenticationService.validToken(rawAdminSession))
            throw new OAuthSmtpException("La sesión administrativa no es válida", 401);
        return startAttempt(actor, AdminAuthenticationService.hash(rawAdminSession), false);
    }

    /** Shell-only first-run ceremony. It is not mapped to any HTTP endpoint. */
    @Transactional
    public StartResult startBootstrapAuthorization(String operator, String approvalId, String confirmation) {
        ensureOAuthConfigured();
        if (operator == null || !operator.matches("[A-Za-z0-9._:@/-]{3,100}")
                || approvalId == null || !approvalId.matches("[A-Za-z0-9._:/-]{3,100}")
                || !"AUTHORIZE DEV SIMERTPI OUTLOOK SMTP".equals(confirmation))
            throw new OAuthSmtpException("La autorización inicial requiere aprobación y confirmación explícitas", 400);
        jdbc.execute("SELECT pg_advisory_xact_lock(25250343)");
        Integer administrators = jdbc.queryForObject("SELECT count(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE r.code='IT_ADMIN'", Integer.class);
        if (administrators == null || administrators != 0)
            throw new OAuthSmtpException("La autorización de bootstrap solo se permite antes del primer Superadministrador", 409);
        Integer existingConnection = jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1 AND state<>'DISCONNECTED'", Integer.class);
        if (existingConnection != null && existingConnection != 0)
            throw new OAuthSmtpException("Ya existe una autorización Microsoft; no se iniciará otra", 409);
        return startAttempt("BOOTSTRAP:" + operator, sha256("SIMERTPI-BOOTSTRAP:" + approvalId), true);
    }

    private StartResult startAttempt(String actor, String sessionHash, boolean bootstrap) {
        UUID id = UUID.randomUUID();
        String state = randomToken(32), verifier = randomToken(32), nonce = randomToken(32);
        SmtpCredentialCipher.Encrypted encryptedVerifier = cipher.encrypt(verifier.toCharArray());
        OffsetDateTime now = now();
        jdbc.update("DELETE FROM identity.admin_smtp_oauth_attempt WHERE expires_at < ? OR consumed_at < ?", now, now.minusHours(1));
        jdbc.update("""
                INSERT INTO identity.admin_smtp_oauth_attempt
                  (id,state_hash,nonce,verifier_nonce,verifier_ciphertext,actor,admin_session_hash,expected_sender,bootstrap,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, sha256(state), nonce, encryptedVerifier.nonce(), encryptedVerifier.ciphertext(), actor,
                sessionHash, authorizedSender, bootstrap, now.plusMinutes(10));
        audit.recordOutcome("MICROSOFT_SMTP_OAUTH_STARTED", "SMTP_OAUTH", id, "SUCCESS", Map.of("actor", actor));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256Bytes(verifier));
        String url = AUTHORIZE + "?client_id=" + enc(clientId) + "&response_type=code&redirect_uri=" + enc(redirectUri)
                + "&response_mode=query&scope=" + enc(SCOPE) + "&state=" + enc(state) + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256&prompt=select_account&login_hint=" + enc(authorizedSender);
        return new StartResult(url);
    }

    /** Callback is deliberately state-authenticated: Microsoft does not return SameSite=Strict admin cookies. */
    @Transactional
    public String complete(String state, String code, String providerError) {
        if (state == null || state.length() > 256) return "failed";
        var rows = jdbc.query("""
                SELECT id,nonce,verifier_nonce,verifier_ciphertext,actor,admin_session_hash,expected_sender,bootstrap,expires_at,consumed_at
                  FROM identity.admin_smtp_oauth_attempt WHERE state_hash=? FOR UPDATE
                """, (rs, n) -> new Attempt(rs.getObject("id", UUID.class), rs.getString("nonce"),
                rs.getBytes("verifier_nonce"), rs.getBytes("verifier_ciphertext"), rs.getString("actor"),
                rs.getString("admin_session_hash"), rs.getString("expected_sender"), rs.getBoolean("bootstrap"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("consumed_at", OffsetDateTime.class)), sha256(state));
        if (rows.isEmpty()) return "failed";
        Attempt attempt = rows.getFirst();
        if (attempt.consumedAt() != null || !attempt.expiresAt().isAfter(now()) || !isStillAuthorized(attempt)) return "failed";
        jdbc.update("UPDATE identity.admin_smtp_oauth_attempt SET consumed_at=? WHERE id=? AND consumed_at IS NULL", now(), attempt.id());
        if (providerError != null || code == null || code.isBlank() || code.length() > 4096) {
            audit.recordOutcome("MICROSOFT_SMTP_OAUTH_COMPLETED", "SMTP_OAUTH", attempt.id(), "FAILURE", Map.of("reason", "PROVIDER_REJECTED"));
            return "failed";
        }
        char[] verifier = cipher.decrypt(attempt.verifierNonce(), attempt.verifierCiphertext());
        try {
            MicrosoftOAuthClient.TokenResponse tokens = oauth.exchange(code, new String(verifier), redirectUri);
            MicrosoftOAuthClient.AuthorizedIdentity identity = oauth.validateIdentity(tokens.idToken(), attempt.nonce());
            if (!normalizeEmail(identity.email()).equals(attempt.expectedSender())) {
                audit.recordOutcome("MICROSOFT_SMTP_OAUTH_COMPLETED", "SMTP_OAUTH", attempt.id(), "FAILURE", Map.of("reason", "ACCOUNT_MISMATCH"));
                return "failed";
            }
            saveTokens(tokens, identity.email(), identity.subject(), attempt.actor(), false, null);
            audit.recordOutcome("MICROSOFT_SMTP_OAUTH_COMPLETED", "SMTP_OAUTH", attempt.id(), "SUCCESS", Map.of("account", identity.email()));
            return "connected";
        } catch (RuntimeException failure) {
            audit.recordOutcome("MICROSOFT_SMTP_OAUTH_COMPLETED", "SMTP_OAUTH", attempt.id(), "FAILURE", Map.of("reason", "TOKEN_EXCHANGE_FAILED"));
            return "failed";
        } finally { Arrays.fill(verifier, '\0'); }
    }

    @Transactional(readOnly = true)
    public OAuthStatus status() {
        if (!isConfigured()) return new OAuthStatus("NOT_CONFIGURED", null, null, null, null, null, false);
        var rows = jdbc.query("SELECT state,sender_email,connected_at,last_refreshed_at,access_expires_at,last_error_code,test_passed_at FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1",
                (rs, n) -> new OAuthStatus(rs.getString("state"), rs.getString("sender_email"),
                        rs.getObject("connected_at", OffsetDateTime.class), rs.getObject("last_refreshed_at", OffsetDateTime.class),
                        rs.getObject("access_expires_at", OffsetDateTime.class), rs.getString("last_error_code"),
                        rs.getObject("test_passed_at", OffsetDateTime.class) != null));
        return rows.isEmpty() ? new OAuthStatus("PENDING_AUTHORIZATION", null, null, null, null, null, false) : rows.getFirst();
    }

    @Transactional(readOnly = true)
    public boolean isActive() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1 AND state='ACTIVE'", Integer.class);
        return count != null && count == 1;
    }

    @Transactional(noRollbackFor = OAuthSmtpException.class)
    public OperationResult testConnection(String actor) {
        UUID attempt = beginTestAttempt(actor, "CONNECTION");
        OAuthCredential credential = null;
        char[] accessToken = null;
        try {
            credential = lockCredential(true);
            accessToken = accessToken(credential);
            hostPolicy.validateMicrosoftOAuthHost(SMTP_HOST);
            transport.testConnection(settings(credential.sender()), accessToken, SmtpTransport.Authentication.XOAUTH2);
            jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET test_passed_at=?,last_error_code=NULL,updated_at=?,updated_by=?,row_version=row_version+1 WHERE singleton_id=1", now(), now(), actor);
            finishTestAttempt(attempt, "SUCCESS", null);
            audit.recordOutcome("MICROSOFT_SMTP_CONNECTION_TESTED", "SMTP_OAUTH", null, "SUCCESS", Map.of("account", credential.sender()));
            return new OperationResult("SUCCESS", now());
        } catch (RuntimeException failure) {
            if (credential != null && !(failure instanceof OAuthSmtpException))
                jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET last_error_code='SMTP_CONNECTION_FAILED',updated_at=?,row_version=row_version+1 WHERE singleton_id=1", now());
            finishTestAttempt(attempt, "FAILURE", failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", ""));
            audit.recordOutcome("MICROSOFT_SMTP_CONNECTION_TESTED", "SMTP_OAUTH", null, "FAILURE", Map.of("failureType", failure.getClass().getSimpleName()));
            if (failure instanceof OAuthSmtpException oauthFailure) throw oauthFailure;
            throw new OAuthSmtpException("No fue posible validar la conexión segura con Microsoft SMTP", 502);
        } finally { if (accessToken != null) Arrays.fill(accessToken, '\0'); }
    }

    @Transactional(noRollbackFor = OAuthSmtpException.class)
    public OperationResult sendTestEmail(String actor) {
        if (!testEmailEnabled || testRecipient.isBlank()) throw new OAuthSmtpException("El correo de prueba no está habilitado", 409);
        UUID attempt = beginTestAttempt(actor, "EMAIL");
        OAuthCredential credential = null;
        char[] accessToken = null;
        try {
            credential = lockCredential(true);
            accessToken = accessToken(credential);
            transport.sendTestEmail(settings(credential.sender()), accessToken, testRecipient, SmtpTransport.Authentication.XOAUTH2);
            finishTestAttempt(attempt, "SUCCESS", null);
            audit.recordOutcome("MICROSOFT_SMTP_TEST_EMAIL", "SMTP_OAUTH", null, "SUCCESS", Map.of("account", credential.sender()));
            return new OperationResult("SUCCESS", now());
        } catch (RuntimeException failure) {
            if (credential != null && !(failure instanceof OAuthSmtpException))
                jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET last_error_code='SMTP_TEST_EMAIL_FAILED',updated_at=?,row_version=row_version+1 WHERE singleton_id=1", now());
            finishTestAttempt(attempt, "FAILURE", failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", ""));
            audit.recordOutcome("MICROSOFT_SMTP_TEST_EMAIL", "SMTP_OAUTH", null, "FAILURE", Map.of("failureType", failure.getClass().getSimpleName()));
            if (failure instanceof OAuthSmtpException oauthFailure) throw oauthFailure;
            throw new OAuthSmtpException("Microsoft no aceptó el correo de prueba", 502);
        } finally { if (accessToken != null) Arrays.fill(accessToken, '\0'); }
    }

    @Transactional
    public OperationResult activate(String actor, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new IllegalArgumentException("El motivo es obligatorio");
        OAuthCredential credential = lockCredential(false);
        if (credential.testPassedAt() == null || credential.testPassedAt().isBefore(now().minusMinutes(15)))
            throw new OAuthSmtpException("Prueba la conexión de Microsoft antes de activarla", 409);
        jdbc.update("UPDATE identity.admin_smtp_configuration SET state='DISABLED',reason=?,updated_by=?,updated_at=?,row_version=row_version+1 WHERE state='ACTIVE'", reason.trim(), actor, now());
        jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET state='ACTIVE',updated_by=?,updated_at=?,row_version=row_version+1 WHERE singleton_id=1 AND state='CONNECTED'", actor, now());
        audit.recordOutcome("MICROSOFT_SMTP_ACTIVATED", "SMTP_OAUTH", null, "SUCCESS", Map.of("account", credential.sender()));
        return new OperationResult("ACTIVE", now());
    }

    @Transactional
    public OperationResult disconnect(String actor, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new IllegalArgumentException("El motivo es obligatorio");
        jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET state='DISCONNECTED',access_token_nonce=NULL,access_token_ciphertext=NULL,refresh_token_nonce=NULL,refresh_token_ciphertext=NULL,access_expires_at=?,last_error_code=NULL,test_passed_at=NULL,updated_by=?,updated_at=?,row_version=row_version+1 WHERE singleton_id=1 AND state<>'DISCONNECTED'", now(), actor, now());
        audit.recordOutcome("MICROSOFT_SMTP_DISCONNECTED", "SMTP_OAUTH", null, "SUCCESS", Map.of("reason", reason.trim()));
        return new OperationResult("DISCONNECTED", now());
    }

    /** Returns an access token for the delivery adapter. Caller must clear it after use. */
    @Transactional(noRollbackFor = OAuthSmtpException.class)
    public DeliveryCredential activeCredential() {
        OAuthCredential credential = lockCredential(true);
        return new DeliveryCredential(settings(credential.sender()), accessToken(credential));
    }

    private char[] accessToken(OAuthCredential credential) {
        OffsetDateTime current = now();
        if (credential.accessExpiresAt().isAfter(current.plusMinutes(3)))
            return cipher.decrypt(credential.accessNonce(), credential.accessCiphertext());
        char[] refresh = cipher.decrypt(credential.refreshNonce(), credential.refreshCiphertext());
        try {
            MicrosoftOAuthClient.TokenResponse response = oauth.refresh(new String(refresh));
            if (response.refreshToken() == null || response.refreshToken().isBlank()) {
                char[] oldRefresh = refresh;
                char[] access = response.accessToken().toCharArray();
                SmtpCredentialCipher.Encrypted encryptedAccess = cipher.encrypt(access);
                SmtpCredentialCipher.Encrypted encryptedRefresh = cipher.encrypt(oldRefresh);
                jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET access_token_nonce=?,access_token_ciphertext=?,refresh_token_nonce=?,refresh_token_ciphertext=?,access_expires_at=?,last_refreshed_at=?,last_error_code=NULL,updated_at=?,row_version=row_version+1 WHERE singleton_id=1",
                        encryptedAccess.nonce(), encryptedAccess.ciphertext(), encryptedRefresh.nonce(), encryptedRefresh.ciphertext(), current.plusSeconds(response.expiresInSeconds()), current, current);
                return access;
            }
            saveTokens(response, credential.sender(), credential.subject(), credential.updatedBy(), credential.state().equals("ACTIVE"), current);
            return response.accessToken().toCharArray();
        } catch (MicrosoftOAuthClientException failure) {
            // Only an explicit invalid_grant from the provider requires user interaction; other failures remain retryable.
            String code = safeOAuthCode(failure);
            if (code.isBlank()) code = "TEMPORARY_FAILURE";
            if ("INVALID_GRANT".equals(code)) {
                jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET state='REAUTH_REQUIRED',last_error_code='INVALID_GRANT',updated_at=?,row_version=row_version+1 WHERE singleton_id=1", current);
            } else {
                jdbc.update("UPDATE identity.admin_smtp_oauth_connection SET last_error_code=?,updated_at=?,row_version=row_version+1 WHERE singleton_id=1", code, current);
            }
            throw new OAuthSmtpException("La autorización Microsoft no está disponible para el envío", 502);
        } finally { Arrays.fill(refresh, '\0'); }
    }

    private String safeOAuthCode(RuntimeException failure) {
        return failure instanceof MicrosoftOAuthClientException oauthFailure ? oauthFailure.code() : "";
    }

    private void saveTokens(MicrosoftOAuthClient.TokenResponse tokens, String sender, String subject, String actor,
                            boolean active, OffsetDateTime lastRefresh) {
        if (tokens.accessToken() == null || tokens.accessToken().isBlank() || tokens.expiresInSeconds() < 60 || tokens.expiresInSeconds() > 86400)
            throw new IllegalStateException("Microsoft returned an invalid access token response");
        char[] refresh = tokens.refreshToken() == null || tokens.refreshToken().isBlank() ? null : tokens.refreshToken().toCharArray();
        if (refresh == null) throw new IllegalStateException("Microsoft did not provide offline access");
        char[] access = tokens.accessToken().toCharArray();
        try {
            SmtpCredentialCipher.Encrypted encryptedAccess = cipher.encrypt(access), encryptedRefresh = cipher.encrypt(refresh);
            jdbc.update("""
                INSERT INTO identity.admin_smtp_oauth_connection
                  (singleton_id,state,sender_email,microsoft_subject,access_token_nonce,access_token_ciphertext,
                   refresh_token_nonce,refresh_token_ciphertext,access_expires_at,connected_at,last_refreshed_at,
                   last_error_code,test_passed_at,updated_by,updated_at,row_version)
                VALUES (1,?,?,?,?,?,?,?,?,now(),?,NULL,NULL,?,now(),0)
                ON CONFLICT(singleton_id) DO UPDATE SET state=EXCLUDED.state,sender_email=EXCLUDED.sender_email,
                  microsoft_subject=EXCLUDED.microsoft_subject,access_token_nonce=EXCLUDED.access_token_nonce,
                  access_token_ciphertext=EXCLUDED.access_token_ciphertext,refresh_token_nonce=EXCLUDED.refresh_token_nonce,
                  refresh_token_ciphertext=EXCLUDED.refresh_token_ciphertext,access_expires_at=EXCLUDED.access_expires_at,
                  connected_at=CASE WHEN EXCLUDED.last_refreshed_at IS NULL THEN now() ELSE identity.admin_smtp_oauth_connection.connected_at END,
                  last_refreshed_at=EXCLUDED.last_refreshed_at,last_error_code=NULL,
                  test_passed_at=NULL,updated_by=EXCLUDED.updated_by,updated_at=now(),row_version=identity.admin_smtp_oauth_connection.row_version+1
                """, active ? "ACTIVE" : "CONNECTED", normalizeEmail(sender), subject, encryptedAccess.nonce(), encryptedAccess.ciphertext(),
                    encryptedRefresh.nonce(), encryptedRefresh.ciphertext(), now().plusSeconds(tokens.expiresInSeconds()),
                    lastRefresh, actor);
        } finally { Arrays.fill(access, '\0'); Arrays.fill(refresh, '\0'); }
    }

    private OAuthCredential lockCredential(boolean requireActive) {
        String sql = "SELECT state,sender_email,microsoft_subject,access_token_nonce,access_token_ciphertext,refresh_token_nonce,refresh_token_ciphertext,access_expires_at,test_passed_at,updated_by FROM identity.admin_smtp_oauth_connection WHERE singleton_id=1 FOR UPDATE";
        var rows = jdbc.query(sql, (rs, n) -> new OAuthCredential(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBytes(4), rs.getBytes(5), rs.getBytes(6), rs.getBytes(7), rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class), rs.getString(10)));
        if (rows.isEmpty()) throw new OAuthSmtpException("Microsoft todavía no está autorizado", 409);
        OAuthCredential credential = rows.getFirst();
        if (!credential.state().equals("CONNECTED") && !(requireActive && credential.state().equals("ACTIVE")))
            throw new OAuthSmtpException("La autorización Microsoft requiere atención", 409);
        return credential;
    }

    private UUID beginTestAttempt(String actor, String kind) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, "microsoft-smtp-test-" + actor + "-" + kind); statement.execute();
            }
            return null;
        });
        Integer recent = jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_oauth_test_attempt WHERE actor=? AND test_kind=? AND created_at>=now()-interval '15 minutes'", Integer.class, actor, kind);
        if (recent != null && recent >= 3) throw new OAuthSmtpException("Se alcanzó el límite temporal de pruebas SMTP", 429);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.admin_smtp_oauth_test_attempt(id,test_kind,result,actor) VALUES (?,?, 'STARTED',?)", id, kind, actor);
        return id;
    }

    private void finishTestAttempt(UUID id,String result,String failureCode) {
        jdbc.update("UPDATE identity.admin_smtp_oauth_test_attempt SET result=?,failure_code=? WHERE id=?", result, failureCode, id);
    }

    private boolean isStillAuthorized(Attempt attempt) {
        if (attempt.bootstrap()) {
            Integer administrators = jdbc.queryForObject("SELECT count(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE r.code='IT_ADMIN'", Integer.class);
            return attempt.actor().startsWith("BOOTSTRAP:") && administrators != null && administrators == 0;
        }
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM identity.admin_web_sessions s
              JOIN identity.users u ON u.id=s.user_id
              JOIN identity.user_roles ur ON ur.user_id=u.id
              JOIN identity.roles r ON r.id=ur.role_id AND r.code='IT_ADMIN' AND r.enabled
             WHERE s.token_hash=? AND u.username=? AND u.enabled AND s.revoked_at IS NULL
               AND s.absolute_expires_at>now() AND s.idle_expires_at>now()
            """, Integer.class, attempt.adminSessionHash(), attempt.actor());
        return count != null && count == 1;
    }

    private void ensureOAuthConfigured() {
        if (!isConfigured()) throw new OAuthSmtpException("La aplicación Microsoft OAuth no está configurada", 503);
        try {
            java.net.URI uri = java.net.URI.create(redirectUri);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !"/api/v1/admin/system/email/oauth/callback".equals(uri.getPath())) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new OAuthSmtpException("La URL de retorno Microsoft no está configurada de forma segura", 503);
        }
        if (!authorizedSender.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new OAuthSmtpException("La cuenta remitente Microsoft no está configurada", 503);
    }
    private boolean isConfigured() { return !clientId.isBlank() && clientSecretPresent && !redirectUri.isBlank() && !authorizedSender.isBlank() && cipher.available(); }
    private SmtpTransport.SmtpSettings settings(String sender) { return new SmtpTransport.SmtpSettings(SMTP_HOST, 587, "STARTTLS", sender, sender, "SIMERTPI - GAD Piñas"); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String normalizeEmail(String s) { return trim(s).toLowerCase(java.util.Locale.ROOT); }
    private static String randomToken(int bytes) { byte[] value=new byte[bytes];new SecureRandom().nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private static byte[] sha256Bytes(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); } catch(Exception impossible) { throw new IllegalStateException("SHA-256 unavailable"); } }
    private static String sha256(String value) { return java.util.HexFormat.of().formatHex(sha256Bytes(value)); }

    public record StartResult(String authorizationUrl) { @Override public String toString(){ return "MicrosoftOAuthStart[authorizationUrl=redacted]"; } }
    public record OAuthStatus(String state,String authorizedAccount,OffsetDateTime connectedAt,OffsetDateTime lastRefresh,
                              OffsetDateTime expiresAt,String errorCode,boolean testPassed) { }
    public record OperationResult(String status,OffsetDateTime updatedAt) { }
    public record DeliveryCredential(SmtpTransport.SmtpSettings settings,char[] accessToken) {
        @Override public String toString(){return "MicrosoftOAuthDeliveryCredential[settings="+settings.host()+", accessToken=redacted]";}
    }
    public static final class OAuthSmtpException extends RuntimeException {
        private final int status;
        public OAuthSmtpException(String message,int status){super(message);this.status=status;}
        public int status(){return status;}
    }
    private record Attempt(UUID id,String nonce,byte[] verifierNonce,byte[] verifierCiphertext,String actor,
                           String adminSessionHash,String expectedSender,boolean bootstrap,OffsetDateTime expiresAt,OffsetDateTime consumedAt) { }
    private record OAuthCredential(String state,String sender,String subject,byte[] accessNonce,byte[] accessCiphertext,
                                   byte[] refreshNonce,byte[] refreshCiphertext,OffsetDateTime accessExpiresAt,
                                   OffsetDateTime testPassedAt,String updatedBy) { }
}
