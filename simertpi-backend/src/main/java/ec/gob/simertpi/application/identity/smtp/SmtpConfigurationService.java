package ec.gob.simertpi.application.identity.smtp;

import ec.gob.simertpi.application.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SmtpConfigurationService {
    private final JdbcTemplate jdbc;
    private final SmtpCredentialCipher cipher;
    private final SmtpHostPolicy hostPolicy;
    private final SmtpTransport transport;
    private final AuditService audit;
    private final String testRecipient;
    private final boolean testEmailEnabled;

    public SmtpConfigurationService(JdbcTemplate jdbc, SmtpCredentialCipher cipher, SmtpHostPolicy hostPolicy,
                                    SmtpTransport transport, AuditService audit,
                                    @Value("${simertpi.smtp.test-recipient:}") String testRecipient,
                                    @Value("${simertpi.smtp.test-email-enabled:false}") boolean testEmailEnabled) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.hostPolicy = hostPolicy;
        this.transport = transport;
        this.audit = audit;
        this.testRecipient = testRecipient == null ? "" : testRecipient.trim();
        this.testEmailEnabled = testEmailEnabled;
    }

    @Transactional(readOnly = true)
    public Snapshot getConfiguration() {
        List<ConfigView> configs = jdbc.query("""
                SELECT id, version, provider, display_name, from_address, smtp_host, smtp_port, security_mode,
                       smtp_username, state, created_by, created_at, updated_by, updated_at, activated_at, reason
                FROM identity.admin_smtp_configuration ORDER BY version DESC LIMIT 50
                """, (rs, row) -> view(rs));
        ConfigView active = configs.stream().filter(c -> "ACTIVE".equals(c.state())).findFirst().orElse(null);
        ConfigView draft = configs.stream().filter(c -> "DRAFT".equals(c.state())).findFirst().orElse(null);
        List<HistoryEvent> history = jdbc.query("""
                SELECT a.id, c.version, a.action, a.actor_name, a.occurred_at, a.result,
                       COALESCE(c.reason, a.metadata->>'reason') AS reason, c.provider, c.smtp_host, c.from_address
                FROM audit.functional_audit_log a
                LEFT JOIN identity.admin_smtp_configuration c ON c.id=a.resource_id
                WHERE a.resource_type='SMTP_CONFIGURATION'
                ORDER BY a.occurred_at DESC LIMIT 100
                """, (rs, row) -> new HistoryEvent(rs.getObject("id", UUID.class), (Long) rs.getObject("version"),
                    rs.getString("action"), rs.getString("actor_name"), rs.getObject("occurred_at", OffsetDateTime.class),
                    rs.getString("result"), rs.getString("reason"), rs.getString("provider"), rs.getString("smtp_host"), rs.getString("from_address")));
        return new Snapshot(active, draft, history, testEmailEnabled && !testRecipient.isBlank());
    }

    @Transactional
    public ConfigView saveDraft(SmtpCandidate candidate, String actor, String reason) {
        validate(candidate);
        Existing credentialSource = latestDraftOrActive();
        char[] supplied = candidate.password();
        SmtpCredentialCipher.Encrypted encrypted;
        if (supplied != null && supplied.length > 0) {
            try {
                if (supplied.length > 512) throw new IllegalArgumentException("La contraseña SMTP supera el tamaño permitido");
                encrypted = cipher.encrypt(supplied);
            } finally { java.util.Arrays.fill(supplied, '\0'); }
        } else if (credentialSource != null) {
            encrypted = new SmtpCredentialCipher.Encrypted(credentialSource.nonce(), credentialSource.ciphertext());
        } else {
            throw new IllegalArgumentException("Ingresa la contraseña de aplicación SMTP para guardar la primera configuración");
        }
        jdbc.update("UPDATE identity.admin_smtp_configuration SET state='SUPERSEDED', updated_at=now(), updated_by=? WHERE state='DRAFT'", actor);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO identity.admin_smtp_configuration
                  (id, provider, display_name, from_address, smtp_host, smtp_port, security_mode, smtp_username,
                   credential_nonce, credential_ciphertext, state, reason, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, id, candidate.provider(), clean(candidate.displayName()), candidate.fromAddress().trim(),
                candidate.host().trim().toLowerCase(java.util.Locale.ROOT), candidate.port(), candidate.security(),
                candidate.username().trim(), encrypted.nonce(), encrypted.ciphertext(), safeReason(reason), actor, actor);
        audit.recordOutcome("SMTP_DRAFT_SAVED", "SMTP_CONFIGURATION", id, "SUCCESS", Map.of(
                "provider", candidate.provider(), "host", candidate.host().trim(), "port", candidate.port(), "security", candidate.security()));
        return find(id);
    }

    @Transactional
    public OperationResult testConnection(long version, String actor) {
        Stored stored = byVersion(version, "DRAFT");
        if (stored == null) throw new SmtpOperationException("El borrador SMTP ya no existe o fue reemplazado", 404);
        checkRate(actor, "CONNECTION");
        char[] secret = cipher.decrypt(stored.nonce(), stored.ciphertext());
        UUID attemptId = insertAttempt(stored.id(), "CONNECTION", "STARTED", actor, null, null);
        try {
            hostPolicy.validate(stored.host());
            transport.testConnection(stored.settings(), secret);
            updateAttempt(attemptId, "SUCCESS", null);
            audit.recordOutcome("SMTP_CONNECTION_TESTED", "SMTP_CONFIGURATION", stored.id(), "SUCCESS", Map.of("version", version));
            return new OperationResult("SUCCESS", version, OffsetDateTime.now());
        } catch (RuntimeException failure) {
            updateAttempt(attemptId, "FAILURE", safeFailureCode(failure));
            audit.recordOutcome("SMTP_CONNECTION_TESTED", "SMTP_CONFIGURATION", stored.id(), "FAILURE", Map.of("version", version, "failureType", failure.getClass().getSimpleName()));
            return new OperationResult("FAILURE", version, OffsetDateTime.now());
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    @Transactional
    public OperationResult sendTestEmail(String actor) {
        if (!testEmailEnabled || testRecipient.isBlank()) throw new SmtpOperationException("El envío de prueba no está habilitado en este ambiente");
        Stored stored = latestDraft();
        if (stored == null) throw new SmtpOperationException("Guarda un borrador SMTP antes de enviar una prueba", 409);
        checkRate(actor, "EMAIL");
        char[] secret = cipher.decrypt(stored.nonce(), stored.ciphertext());
        String fingerprint = sha256(testRecipient.toLowerCase(java.util.Locale.ROOT));
        UUID attemptId = insertAttempt(stored.id(), "EMAIL", "STARTED", actor, fingerprint, null);
        try {
            hostPolicy.validate(stored.host());
            transport.sendTestEmail(stored.settings(), secret, testRecipient);
            updateAttempt(attemptId, "SUCCESS", null);
            audit.recordOutcome("SMTP_TEST_EMAIL_SENT", "SMTP_CONFIGURATION", stored.id(), "SUCCESS", Map.of("version", stored.version()));
            return new OperationResult("SUCCESS", stored.version(), OffsetDateTime.now());
        } catch (RuntimeException failure) {
            updateAttempt(attemptId, "FAILURE", safeFailureCode(failure));
            audit.recordOutcome("SMTP_TEST_EMAIL_SENT", "SMTP_CONFIGURATION", stored.id(), "FAILURE", Map.of("version", stored.version(), "failureType", failure.getClass().getSimpleName()));
            return new OperationResult("FAILURE", stored.version(), OffsetDateTime.now());
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    @Transactional
    public OperationResult activate(long version, String reason, String actor) {
        Stored target = byVersion(version, "DRAFT");
        if (target == null) throw new SmtpOperationException("El borrador SMTP ya no existe o fue reemplazado", 404);
        Integer tests = jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_test_attempt WHERE configuration_id=? AND test_kind='CONNECTION' AND result='SUCCESS' AND created_at >= now() - interval '15 minutes'", Integer.class, target.id());
        if (tests == null || tests < 1) throw new SmtpOperationException("Prueba primero la conexión de esta versión antes de activarla", 409);
        jdbc.update("UPDATE identity.admin_smtp_configuration SET state='DISABLED', updated_at=now(), updated_by=? WHERE state='ACTIVE'", actor);
        int updated = jdbc.update("""
                UPDATE identity.admin_smtp_configuration SET state='ACTIVE', reason=?, activated_at=now(), updated_at=now(), updated_by=?, row_version=row_version+1
                WHERE id=? AND state='DRAFT' AND version=?
                """, safeReason(reason), actor, target.id(), version);
        if (updated != 1) throw new SmtpOperationException("La configuración cambió. Actualiza e intenta nuevamente", 409);
        audit.recordOutcome("SMTP_CONFIGURATION_ACTIVATED", "SMTP_CONFIGURATION", target.id(), "SUCCESS", Map.of("version", version));
        return new OperationResult("ACTIVE", version, OffsetDateTime.now());
    }

    @Transactional
    public OperationResult disable(String reason, String actor) {
        Stored active = activeStored();
        if (active == null) throw new SmtpOperationException("No existe una configuración SMTP activa", 409);
        int updated = jdbc.update("UPDATE identity.admin_smtp_configuration SET state='DISABLED', reason=?, updated_at=now(), updated_by=?, row_version=row_version+1 WHERE id=? AND state='ACTIVE'", safeReason(reason), actor, active.id());
        if (updated == 0) throw new SmtpOperationException("La configuración SMTP cambió. Actualiza e intenta nuevamente", 409);
        audit.recordOutcome("SMTP_CONFIGURATION_DISABLED", "SMTP_CONFIGURATION", active.id(), "SUCCESS", Map.of("reason", safeReason(reason), "version", active.version()));
        return new OperationResult("DISABLED", active.version(), OffsetDateTime.now());
    }

    /** Non-web, protected bootstrap API. Probes before persistence and never sends email. */
    @Transactional
    public BootstrapResult bootstrap(SmtpCandidate candidate, String actor, String reason) {
        validate(candidate);
        if (hasActive()) throw new IllegalStateException("Ya existe una configuración SMTP activa");
        if (latestDraft() != null) throw new IllegalStateException("Existe una configuración SMTP pendiente");
        char[] secret = candidate.password();
        if (secret == null || secret.length == 0) throw new IllegalArgumentException("La contraseña de aplicación SMTP es obligatoria");
        try {
            hostPolicy.validate(candidate.host());
            transport.testConnection(settings(candidate), secret);
            SmtpCredentialCipher.Encrypted encrypted = cipher.encrypt(secret);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO identity.admin_smtp_configuration
                      (id, provider, display_name, from_address, smtp_host, smtp_port, security_mode, smtp_username,
                       credential_nonce, credential_ciphertext, state, reason, created_by, updated_by, activated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, now())
                    """, id, candidate.provider(), clean(candidate.displayName()), candidate.fromAddress().trim(),
                    candidate.host().trim().toLowerCase(java.util.Locale.ROOT), candidate.port(), candidate.security(),
                    candidate.username().trim(), encrypted.nonce(), encrypted.ciphertext(), safeReason(reason), actor, actor);
            long version = jdbc.queryForObject("SELECT version FROM identity.admin_smtp_configuration WHERE id=?", Long.class, id);
            audit.recordOutcome("SMTP_BOOTSTRAPPED", "SMTP_CONFIGURATION", id, "SUCCESS", Map.of("version", version, "provider", candidate.provider()));
            return new BootstrapResult(version, "ACTIVE", candidate.provider(), candidate.fromAddress(), candidate.host(), candidate.port(), candidate.security(), OffsetDateTime.now());
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    @Transactional(readOnly = true)
    public ActiveCredential activeCredential() {
        Stored stored;
        try { stored = jdbc.queryForObject("""
                SELECT id, version, provider, display_name, from_address, smtp_host, smtp_port, security_mode,
                       smtp_username, credential_nonce, credential_ciphertext, state, updated_at
                FROM identity.admin_smtp_configuration WHERE state='ACTIVE'
                """, (rs, row) -> stored(rs)); }
        catch (EmptyResultDataAccessException missing) { return null; }
        if (stored == null) return null;
        char[] secret = cipher.decrypt(stored.nonce(), stored.ciphertext());
        return new ActiveCredential(stored.settings(), secret, stored.version());
    }

    public boolean configured() { return hasActive(); }

    private void validate(SmtpCandidate c) {
        if (c == null || !List.of("GMAIL", "CUSTOM").contains(c.provider())
                || !List.of("STARTTLS", "SMTPS").contains(c.security())
                || c.displayName() == null || c.displayName().isBlank() || c.displayName().length() > 150
                || c.fromAddress() == null || !c.fromAddress().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || c.fromAddress().length() > 255
                || c.host() == null || c.host().isBlank() || c.host().length() > 253
                || c.username() == null || c.username().isBlank() || c.username().length() > 255
                || c.port() < 1 || c.port() > 65535) throw new IllegalArgumentException("Los datos de configuración SMTP no son válidos");
        if ("GMAIL".equals(c.provider()) && (!"smtp.gmail.com".equalsIgnoreCase(c.host().trim())
                || !(c.port() == 587 && "STARTTLS".equals(c.security()) || c.port() == 465 && "SMTPS".equals(c.security())))) {
            throw new IllegalArgumentException("La configuración de Gmail debe usar smtp.gmail.com con STARTTLS/587 o SMTPS/465");
        }
    }

    private Existing latestDraftOrActive() {
        try { return jdbc.queryForObject("SELECT credential_nonce, credential_ciphertext FROM identity.admin_smtp_configuration WHERE state IN ('DRAFT','ACTIVE') ORDER BY CASE state WHEN 'DRAFT' THEN 0 ELSE 1 END LIMIT 1", (rs, row) -> new Existing(rs.getBytes(1), rs.getBytes(2))); }
        catch (EmptyResultDataAccessException none) { return null; }
    }
    private Stored latestDraft() { try { return jdbc.queryForObject("SELECT id,version,provider,display_name,from_address,smtp_host,smtp_port,security_mode,smtp_username,credential_nonce,credential_ciphertext,state,updated_at FROM identity.admin_smtp_configuration WHERE state='DRAFT'", (rs,row)->stored(rs)); } catch (EmptyResultDataAccessException none) { return null; } }
    private Stored byVersion(long version, String state) { try { return jdbc.queryForObject("SELECT id,version,provider,display_name,from_address,smtp_host,smtp_port,security_mode,smtp_username,credential_nonce,credential_ciphertext,state,updated_at FROM identity.admin_smtp_configuration WHERE version=? AND state=?", (rs,row)->stored(rs), version, state); } catch (EmptyResultDataAccessException none) { return null; } }
    private ConfigView find(UUID id) { return jdbc.queryForObject("SELECT id,version,provider,display_name,from_address,smtp_host,smtp_port,security_mode,smtp_username,state,created_by,created_at,updated_by,updated_at,activated_at,reason FROM identity.admin_smtp_configuration WHERE id=?", (rs,row)->view(rs), id); }
    private boolean hasActive() { Integer count=jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_configuration WHERE state='ACTIVE'", Integer.class); return count != null && count > 0; }
    private Stored activeStored() { try { return jdbc.queryForObject("SELECT id,version,provider,display_name,from_address,smtp_host,smtp_port,security_mode,smtp_username,credential_nonce,credential_ciphertext,state,updated_at FROM identity.admin_smtp_configuration WHERE state='ACTIVE'", (rs,row)->stored(rs)); } catch (EmptyResultDataAccessException none) { return null; } }
    private Stored stored(java.sql.ResultSet rs) throws java.sql.SQLException { return new Stored(rs.getObject("id",UUID.class),rs.getLong("version"),rs.getString("provider"),rs.getString("display_name"),rs.getString("from_address"),rs.getString("smtp_host"),rs.getInt("smtp_port"),rs.getString("security_mode"),rs.getString("smtp_username"),rs.getBytes("credential_nonce"),rs.getBytes("credential_ciphertext"),rs.getString("state"),rs.getObject("updated_at",OffsetDateTime.class)); }
    private ConfigView view(java.sql.ResultSet rs) throws java.sql.SQLException { return new ConfigView(rs.getObject("id",UUID.class),rs.getLong("version"),rs.getString("provider"),rs.getString("display_name"),rs.getString("from_address"),rs.getString("smtp_host"),rs.getInt("smtp_port"),rs.getString("security_mode"),rs.getString("smtp_username"),true,rs.getString("state"),rs.getString("created_by"),rs.getObject("created_at",OffsetDateTime.class),rs.getString("updated_by"),rs.getObject("updated_at",OffsetDateTime.class),rs.getObject("activated_at",OffsetDateTime.class),rs.getString("reason")); }
    private SmtpTransport.SmtpSettings settings(SmtpCandidate c) { return new SmtpTransport.SmtpSettings(c.host().trim().toLowerCase(java.util.Locale.ROOT),c.port(),c.security(),c.username().trim(),c.fromAddress().trim(),clean(c.displayName())); }
    private SmtpTransport.SmtpSettings settings(Stored s) { return new SmtpTransport.SmtpSettings(s.host(),s.port(),s.security(),s.username(),s.fromAddress(),s.displayName()); }
    private void checkRate(String actor,String kind) { jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> { try (var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) { statement.setString(1,"smtp-test-"+actor+"-"+kind); statement.execute(); } return null; }); Integer count=jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_test_attempt WHERE actor=? AND test_kind=? AND created_at >= now() - interval '15 minutes'",Integer.class,actor,kind); if(count!=null&&count>=3) throw new SmtpOperationException("Se alcanzó el límite temporal de pruebas SMTP", 429); }
    private UUID insertAttempt(UUID id,String kind,String result,String actor,String fingerprint,String failure) { UUID attempt=UUID.randomUUID(); jdbc.update("INSERT INTO identity.admin_smtp_test_attempt(id,configuration_id,test_kind,result,actor,recipient_fingerprint,failure_code) VALUES (?,?,?,?,?,?,?)",attempt,id,kind,result,actor,fingerprint,failure); return attempt; }
    private void updateAttempt(UUID id,String result,String failure) { jdbc.update("UPDATE identity.admin_smtp_test_attempt SET result=?,failure_code=? WHERE id=?",result,failure,id); }
    private static String safeFailureCode(RuntimeException failure) { return failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", ""); }
    private static String safeReason(String reason) { if(reason==null||reason.isBlank()||reason.length()>500) throw new IllegalArgumentException("El motivo es obligatorio y no debe superar 500 caracteres"); return reason.trim(); }
    private static String clean(String value) { return value.replaceAll("[\\p{Cntrl}]", "").trim(); }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }

    public record ConfigView(UUID id,long version,String provider,String displayName,String from,String host,int port,String security,String username,boolean credentialConfigured,String state,String createdBy,OffsetDateTime createdAt,String updatedBy,OffsetDateTime updatedAt,OffsetDateTime activatedAt,String reason) { }
    public record HistoryEvent(UUID id,Long version,String action,String actor,OffsetDateTime occurredAt,String result,String reason,String provider,String host,String from) { }
    public record Snapshot(ConfigView active,ConfigView draft,List<HistoryEvent> history,boolean testRecipientConfigured) { }
    public record OperationResult(String status,Long version,OffsetDateTime updatedAt) { }
    public record BootstrapResult(long version,String status,String provider,String from,String host,int port,String security,OffsetDateTime activatedAt) { }
    public record ActiveCredential(SmtpTransport.SmtpSettings settings,char[] password,long version) {
        @Override public String toString() { return "ActiveCredential[version="+version+", password=redacted]"; }
    }
    private record Existing(byte[] nonce,byte[] ciphertext) {
        @Override public String toString() { return "ExistingCredential[encrypted=true]"; }
    }
    private record Stored(UUID id,long version,String provider,String displayName,String fromAddress,String host,int port,String security,String username,byte[] nonce,byte[] ciphertext,String state,OffsetDateTime updatedAt) {
        private SmtpTransport.SmtpSettings settings() { return new SmtpTransport.SmtpSettings(host,port,security,username,fromAddress,displayName); }
        @Override public String toString() { return "StoredSmtpConfiguration[id="+id+",version="+version+",state="+state+",credential=encrypted]"; }
    }
}
