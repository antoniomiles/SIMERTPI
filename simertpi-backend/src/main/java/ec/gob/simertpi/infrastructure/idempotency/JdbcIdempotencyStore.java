package ec.gob.simertpi.infrastructure.idempotency;

import ec.gob.simertpi.application.idempotency.IdempotencyRecord;
import ec.gob.simertpi.application.idempotency.ParkingSessionIdempotencyStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcIdempotencyStore implements ParkingSessionIdempotencyStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIdempotencyStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(String key, String operation, UUID userId, String requestHash,
                         OffsetDateTime now, OffsetDateTime expiresAt) {
        String sql = """
                INSERT INTO audit.idempotency_keys
                    (id, idempotency_key, operation_type, user_id, request_hash, status,
                     expires_at, created_at, updated_at)
                VALUES (:id, :key, :operation, :userId, :requestHash, 'PROCESSING',
                        :expiresAt, :now, :now)
                ON CONFLICT (idempotency_key) DO UPDATE
                SET id = EXCLUDED.id,
                    operation_type = EXCLUDED.operation_type,
                    user_id = EXCLUDED.user_id,
                    request_hash = EXCLUDED.request_hash,
                    response_status = NULL,
                    response_body = NULL,
                    status = 'PROCESSING',
                    expires_at = EXCLUDED.expires_at,
                    created_at = EXCLUDED.created_at,
                    updated_at = EXCLUDED.updated_at
                WHERE audit.idempotency_keys.expires_at <= :now
                RETURNING id
                """;
        List<UUID> claimed = jdbc.query(sql, parameters(key, operation, userId, requestHash,
                now, expiresAt), (rs, row) -> rs.getObject(1, UUID.class));
        return !claimed.isEmpty();
    }

    @Override
    public IdempotencyRecord find(String key) {
        return jdbc.queryForObject("""
                SELECT user_id, operation_type, request_hash, response_status, response_body, status
                FROM audit.idempotency_keys WHERE idempotency_key = :key
                """, new MapSqlParameterSource("key", key), (rs, row) -> new IdempotencyRecord(
                rs.getObject("user_id", UUID.class), rs.getString("operation_type"),
                rs.getString("request_hash"), (Integer) rs.getObject("response_status"),
                rs.getString("response_body"), rs.getString("status")));
    }

    @Override
    public void complete(String key, int responseStatus, String responseBody, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE audit.idempotency_keys
                SET status = 'COMPLETED', response_status = :responseStatus,
                    response_body = :responseBody, updated_at = :now
                WHERE idempotency_key = :key AND status = 'PROCESSING'
                """, new MapSqlParameterSource("key", key)
                .addValue("responseStatus", responseStatus)
                .addValue("responseBody", responseBody)
                .addValue("now", now));
        if (updated != 1) {
            throw new IllegalStateException("Idempotency claim was lost during processing");
        }
    }

    private MapSqlParameterSource parameters(String key, String operation, UUID userId,
                                              String requestHash, OffsetDateTime now,
                                              OffsetDateTime expiresAt) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("key", key)
                .addValue("operation", operation)
                .addValue("userId", userId)
                .addValue("requestHash", requestHash)
                .addValue("now", now)
                .addValue("expiresAt", expiresAt);
    }
}
