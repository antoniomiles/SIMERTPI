package ec.gob.simertpi.application.identity.invitation;

import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits throttling state independently of invalid activation requests, which roll back. */
@Service
public class ActivationAttemptThrottleService {
    private final JdbcTemplate jdbc;

    public ActivationAttemptThrottleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean isLimited(String sourceAddress) {
        if (sourceAddress == null || sourceAddress.isBlank()) return false;
        String key = AdminAuthenticationService.hash("activation-ip:" + sourceAddress.trim());
        Boolean blocked = jdbc.queryForObject("""
            INSERT INTO identity.activation_attempt_throttles(attempt_key,window_started_at,attempts,blocked_until,updated_at)
            VALUES (?,CURRENT_TIMESTAMP,1,NULL,CURRENT_TIMESTAMP)
            ON CONFLICT(attempt_key) DO UPDATE SET
              attempts=CASE WHEN identity.activation_attempt_throttles.updated_at < CURRENT_TIMESTAMP-INTERVAL '15 minutes'
                            THEN 1 ELSE LEAST(identity.activation_attempt_throttles.attempts+1,100) END,
              window_started_at=CASE WHEN identity.activation_attempt_throttles.updated_at < CURRENT_TIMESTAMP-INTERVAL '15 minutes'
                                     THEN CURRENT_TIMESTAMP ELSE identity.activation_attempt_throttles.window_started_at END,
              blocked_until=CASE WHEN identity.activation_attempt_throttles.blocked_until > CURRENT_TIMESTAMP
                                 THEN identity.activation_attempt_throttles.blocked_until
                                 WHEN identity.activation_attempt_throttles.updated_at < CURRENT_TIMESTAMP-INTERVAL '15 minutes'
                                 THEN NULL
                                 WHEN identity.activation_attempt_throttles.attempts >= 19
                                 THEN CURRENT_TIMESTAMP+INTERVAL '15 minutes' ELSE NULL END,
              updated_at=CURRENT_TIMESTAMP
            RETURNING COALESCE(blocked_until > CURRENT_TIMESTAMP,FALSE)
            """, Boolean.class, key);
        jdbc.update("DELETE FROM identity.activation_attempt_throttles WHERE updated_at < CURRENT_TIMESTAMP-INTERVAL '2 days'");
        return Boolean.TRUE.equals(blocked);
    }
}
