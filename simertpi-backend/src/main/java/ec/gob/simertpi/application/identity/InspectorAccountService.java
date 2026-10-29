package ec.gob.simertpi.application.identity;

import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.application.audit.Audited;
import ec.gob.simertpi.domain.identity.entity.Role;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.RoleRepository;
import ec.gob.simertpi.domain.identity.repository.InspectorDirectoryProjection;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class InspectorAccountService {
    private static final String INSPECTOR = "INSPECTOR";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final java.time.Duration ACTIVATION_TTL = java.time.Duration.ofHours(48);

    private final UserRepository users;
    private final RoleRepository roles;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public InspectorAccountService(UserRepository users, RoleRepository roles, JdbcTemplate jdbc,
                                   PasswordEncoder passwords, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InspectorDirectoryProjection> directory(
            String query, Boolean enabled, org.springframework.data.domain.Pageable pageable) {
        // Do not pass a null into the JPQL search predicate: PostgreSQL may infer
        // the nullable parameter as bytea when it is used with LOWER/LIKE.
        String normalized = query == null ? "" : query.trim();
        return users.searchInspectors(normalized, enabled, pageable);
    }

    @Transactional
    @Audited(action = "INSPECTOR_CREATED", resourceType = "USER")
    public CreatedInspector create(String username, String email, String firstName, String lastName, String phone) {
        return createWithRoles(username, email, firstName, lastName, phone, List.of(INSPECTOR));
    }

    /** Creates an invited account using only existing roles; the caller must enforce administrator policy. */
    @Transactional
    public CreatedInspector createWithRoles(String username, String email, String firstName, String lastName, String phone,
                                           List<String> requestedRoles) {
        String normalizedUsername = clean(username);
        String normalizedEmail = clean(email).toLowerCase(Locale.ROOT);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('username:' || lower(?), 0))", rs -> null, normalizedUsername);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(lower(?), 0))", rs -> null, normalizedEmail);
        if (users.existsByUsernameIgnoreCase(normalizedUsername) || users.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new InspectorAlreadyExistsException();
        }
        List<String> roleCodes = requestedRoles == null ? List.of() : requestedRoles.stream()
                .filter(java.util.Objects::nonNull).map(code -> code.trim().toUpperCase(Locale.ROOT)).distinct().sorted().toList();
        if (roleCodes.isEmpty() || requestedRoles == null || roleCodes.size() != requestedRoles.size()
                || roleCodes.contains("IT_ADMIN") && roleCodes.contains("SIMERTPI_ADMIN")) {
            throw new IllegalArgumentException("La combinación de roles solicitada no es válida.");
        }
        List<Role> assignedRoles = roleCodes.stream().map(code -> roles.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("El rol solicitado no existe en el catálogo."))).toList();
        OffsetDateTime now = OffsetDateTime.now();
        UUID id = UUID.randomUUID();
        User user = new User();
        user.setId(id);
        user.setUsername(normalizedUsername);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(null);
        user.setFirstName(clean(firstName));
        user.setLastName(clean(lastName));
        user.setPhone(phone == null || phone.isBlank() ? null : clean(phone));
        user.setEnabled(false);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.getRoles().addAll(assignedRoles);
        users.saveAndFlush(user);

        IssuedActivation activation = issueActivation(id, now);
        return new CreatedInspector(id, normalizedUsername, normalizedEmail, user.getFirstName(),
                user.getLastName(), user.getPhone(), false, true, activation.token(), activation.expiresAt());
    }

    @Transactional
    @Audited(action = "INSPECTOR_ACTIVATION_REISSUED", resourceType = "USER", resourceIdArgument = 0)
    public ActivationIssued reissueActivation(UUID userId) {
        jdbc.query("SELECT id FROM identity.users WHERE id = ? FOR UPDATE", rs -> {
            if (!rs.next()) throw new InspectorNotFoundException();
            return rs.getObject(1, UUID.class);
        }, userId);
        User user = users.findById(userId).orElseThrow(InspectorNotFoundException::new);
        if (user.isEnabled() || user.getPasswordHash() != null || user.getRoles().size() != 1
                || !user.getRoles().iterator().next().getCode().equals(INSPECTOR)) {
            throw new InspectorActivationStateException();
        }
        IssuedActivation activation = issueActivation(userId, OffsetDateTime.now());
        return new ActivationIssued(userId, activation.token(), activation.expiresAt());
    }

    @Transactional
    public ActivationIssued reissueInvite(UUID userId) {
        jdbc.query("SELECT id FROM identity.users WHERE id = ? FOR UPDATE", rs -> {
            if (!rs.next()) throw new InspectorNotFoundException();
            return rs.getObject(1, UUID.class);
        }, userId);
        User user = users.findById(userId).orElseThrow(InspectorNotFoundException::new);
        Integer pendingToken = jdbc.queryForObject("SELECT COUNT(*) FROM identity.inspector_activation_tokens WHERE user_id=?", Integer.class, userId);
        if (user.isEnabled() || user.getPasswordHash() != null || user.getRoles().isEmpty() || pendingToken == null || pendingToken != 1) {
            throw new InspectorActivationStateException();
        }
        IssuedActivation activation = issueActivation(userId, OffsetDateTime.now());
        return new ActivationIssued(userId, activation.token(), activation.expiresAt());
    }

    @Transactional
    public void activate(String token, String newPassword) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || newPassword == null
                || newPassword.codePointCount(0, newPassword.length()) < 12
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InspectorActivationRejectedException();
        }
        String tokenHash = hash(token);
        List<UUID> candidates = jdbc.query(
                "SELECT user_id FROM identity.inspector_activation_tokens WHERE token_hash = ? AND expires_at > CURRENT_TIMESTAMP",
                (rs, row) -> rs.getObject(1, UUID.class), tokenHash);
        if (candidates.size() != 1) throw new InspectorActivationRejectedException();

        UUID userId = candidates.getFirst();
        jdbc.query("SELECT id FROM identity.users WHERE id = ? FOR UPDATE", rs -> {
            if (!rs.next()) throw new InspectorActivationRejectedException();
            return rs.getObject(1, UUID.class);
        }, userId);
        List<UUID> currentTokens = jdbc.query(
                "SELECT user_id FROM identity.inspector_activation_tokens WHERE user_id = ? AND token_hash = ? AND expires_at > CURRENT_TIMESTAMP FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), userId, tokenHash);
        if (currentTokens.size() != 1) throw new InspectorActivationRejectedException();
        List<String> roleCodes = jdbc.query(
                "SELECT r.code FROM identity.user_roles ur JOIN identity.roles r ON r.id = ur.role_id WHERE ur.user_id = ?",
                (rs, row) -> rs.getString(1), userId);
        int updated = jdbc.update("""
                UPDATE identity.users SET password_hash = ?, enabled = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND enabled = FALSE AND password_hash IS NULL
                """, passwords.encode(newPassword), userId);
        if (updated != 1 || roleCodes.isEmpty() || roleCodes.contains("IT_ADMIN") && roleCodes.contains("SIMERTPI_ADMIN")) {
            throw new InspectorActivationRejectedException();
        }
        jdbc.update("DELETE FROM identity.inspector_activation_tokens WHERE user_id = ?", userId);
        String activationAction = roleCodes.size() == 1 && INSPECTOR.equals(roleCodes.getFirst())
                ? "INSPECTOR_ACTIVATED" : "INVITED_ACCOUNT_ACTIVATED";
        audit.success(activationAction, "USER", userId, null, Map.of("roles", String.join(",", roleCodes.stream().sorted().toList())));
    }

    private IssuedActivation issueActivation(UUID userId, OffsetDateTime now) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Arrays.fill(secret, (byte) 0);
        OffsetDateTime expiresAt = now.plus(ACTIVATION_TTL);
        jdbc.update("""
                INSERT INTO identity.inspector_activation_tokens(user_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET token_hash = EXCLUDED.token_hash, expires_at = EXCLUDED.expires_at, created_at = EXCLUDED.created_at
                """, userId, hash(token), expiresAt, now);
        return new IssuedActivation(token, expiresAt);
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record IssuedActivation(String token, OffsetDateTime expiresAt) { }

    public record CreatedInspector(UUID id, String username, String email, String firstName, String lastName,
                                   String phone, boolean enabled, boolean activationPending,
                                   String activationToken, OffsetDateTime activationExpiresAt) {
        public UUID getId() { return id; }
        @Override public String toString() { return "CreatedInspector[id=" + id + ", activationToken=redacted]"; }
    }

    public record ActivationIssued(UUID id, String activationToken, OffsetDateTime expiresAt) {
        public UUID getId() { return id; }
        @Override public String toString() { return "ActivationIssued[id=" + id + ", activationToken=redacted]"; }
    }
}
