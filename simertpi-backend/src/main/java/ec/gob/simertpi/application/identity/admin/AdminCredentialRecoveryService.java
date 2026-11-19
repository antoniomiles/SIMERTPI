package ec.gob.simertpi.application.identity.admin;

import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminCredentialRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AdminCredentialRecoveryService.class);
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AdminAuthenticationService authentication;
    private final AdminRecoveryMail mail;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final String portalOrigin;
    private final SecureRandom random = new SecureRandom();
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("admin-recovery-", 0).factory());

    public AdminCredentialRecoveryService(JdbcTemplate jdbc, UserRepository users,
            AdminAuthenticationService authentication, AdminRecoveryMail mail, PasswordEncoder passwords,
            TransactionTemplate transactions, @Value("${simertpi.admin-auth.allowed-origin:}") String allowedOrigin) {
        this.jdbc = jdbc;
        this.users = users;
        this.authentication = authentication;
        this.mail = mail;
        this.passwords = passwords;
        this.transactions = transactions;
        this.portalOrigin = httpsOrigin(allowedOrigin);
    }

    public void request(String email, String source, boolean resetPassword) {
        String address = email.strip().toLowerCase(Locale.ROOT);
        if (!allow("request-source:" + source, 60, 10)
                || !allow((resetPassword ? "password:" : "username:") + address, 1, 15)) {
            return;
        }
        try {
            delivery.execute(() -> {
                try {
                    Message message = transactions.execute(status -> prepare(address, resetPassword));
                    if (message != null) {
                        if (message.passwordReset()) {
                            mail.sendPasswordReset(message.recipient(), message.value());
                        } else {
                            mail.sendUsername(message.recipient(), message.value());
                        }
                    }
                } catch (RuntimeException failure) {
                    log.warn("Administrative credential recovery email was not sent ({})",
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException busy) {
            // Keep the response identical even if the delivery queue is full.
        }
    }

    private record Message(String recipient, String value, boolean passwordReset) {
        @Override public String toString() { return "AdminRecoveryMessage[redacted]"; }
    }

    private Message prepare(String address, boolean resetPassword) {
        var matches = jdbc.queryForList(
                "SELECT id FROM identity.users WHERE lower(email) = ? LIMIT 2 FOR UPDATE", UUID.class, address);
        if (matches.size() != 1) return null;
        User user = users.findById(matches.getFirst()).orElse(null);
        if (!eligible(user)) return null;
        if (!resetPassword) {
            return new Message(user.getEmail(), user.getUsername(), false);
        }
        if (portalOrigin == null) {
            throw new IllegalStateException("Administrative HTTPS origin is unavailable");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Arrays.fill(bytes, (byte) 0);
        int issued = jdbc.update("""
                INSERT INTO identity.admin_password_resets AS t (user_id, token_hash, account_stamp, expires_at)
                VALUES (?, ?, ?, clock_timestamp() + interval '30 minutes')
                ON CONFLICT (user_id) DO UPDATE SET token_hash = EXCLUDED.token_hash,
                    account_stamp = EXCLUDED.account_stamp, expires_at = EXCLUDED.expires_at
                WHERE t.expires_at <= clock_timestamp() OR t.account_stamp <> EXCLUDED.account_stamp
                """, user.getId(), hash(token), stamp(user));
        if (issued != 1) return null;
        return new Message(user.getEmail(), portalOrigin + "/restablecer-contrasena#token=" + token, true);
    }

    public boolean reset(String token, String password, String source) {
        if (!allow("reset-source:" + source, 60, 10)) return false;
        if (!AdminAuthenticationService.validToken(token) || password == null || password.isBlank()
                || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            String digest = hash(token);
            var matches = jdbc.queryForList("""
                    SELECT u.id FROM identity.admin_password_resets t JOIN identity.users u ON u.id = t.user_id
                    WHERE t.token_hash = ? AND t.expires_at > clock_timestamp() FOR UPDATE OF u
                    """, UUID.class, digest);
            if (matches.isEmpty()) return false;
            User user = users.findById(matches.getFirst()).orElse(null);
            if (!eligible(user)) return false;
            int consumed = jdbc.update("""
                    DELETE FROM identity.admin_password_resets
                    WHERE user_id = ? AND token_hash = ? AND account_stamp = ? AND expires_at > clock_timestamp()
                    """, user.getId(), digest, stamp(user));
            if (consumed != 1) return false;
            jdbc.update("UPDATE identity.users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    passwords.encode(password), user.getId());
            return true;
        }));
    }

    private boolean eligible(User user) {
        return user != null && user.getPasswordHash() != null && authentication.isRecoveryEligible(user);
    }

    private boolean allow(String bucket, int limit, int minutes) {
        Integer attempts = jdbc.queryForObject("""
                INSERT INTO identity.admin_recovery_throttles AS t (bucket_key, expires_at, attempts)
                VALUES (?, CURRENT_TIMESTAMP + (? * interval '1 minute'), 1)
                ON CONFLICT (bucket_key) DO UPDATE SET
                    attempts = CASE WHEN t.expires_at <= CURRENT_TIMESTAMP THEN 1 ELSE LEAST(t.attempts + 1, ?) END,
                    expires_at = CASE WHEN t.expires_at <= CURRENT_TIMESTAMP THEN EXCLUDED.expires_at ELSE t.expires_at END
                RETURNING attempts
                """, Integer.class, hash(bucket), minutes, limit + 1);
        return attempts != null && attempts <= limit;
    }

    @Scheduled(fixedDelay = 3600000)
    public void deleteExpiredThrottles() {
        jdbc.update("DELETE FROM identity.admin_recovery_throttles WHERE expires_at <= CURRENT_TIMESTAMP");
    }

    @PreDestroy
    void stopDelivery() {
        delivery.shutdownNow();
    }

    private static String stamp(User user) {
        return hash(user.getEmail() + ":" + user.getPasswordHash());
    }

    private static String hash(String value) {
        return AdminAuthenticationService.hash(value);
    }

    private static String httpsOrigin(String configured) {
        try {
            URI uri = URI.create(configured);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                return "https://" + uri.getRawAuthority();
            }
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return null;
    }
}
