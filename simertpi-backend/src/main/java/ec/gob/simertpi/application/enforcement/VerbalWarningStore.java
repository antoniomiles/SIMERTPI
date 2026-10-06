package ec.gob.simertpi.application.enforcement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class VerbalWarningStore {
    public static final int MAX_OBSERVATION = 500;
    private final JdbcTemplate jdbc;
    public VerbalWarningStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Warning(UUID id, UUID parkingSessionId, UUID inspectorId, OffsetDateTime contractEndAt,
                          OffsetDateTime recordedAt, String observation, String actionType, boolean active,
                          String idempotencyKey) { }
    private List<Warning> query(String clause, Object... args) {
        return jdbc.query("SELECT * FROM enforcement.verbal_warnings WHERE " + clause,
                (r,n) -> new Warning(r.getObject("id", UUID.class), r.getObject("parking_session_id", UUID.class),
                        r.getObject("inspector_id", UUID.class), r.getObject("contract_end_at", OffsetDateTime.class),
                        r.getObject("recorded_at", OffsetDateTime.class), r.getString("observation"),
                        r.getString("action_type"), r.getBoolean("active"), r.getString("idempotency_key")), args);
    }
    public Optional<Warning> byKey(UUID inspector, String key) {
        return query("inspector_id=? AND idempotency_key=?", inspector, key).stream().findFirst();
    }
    public Optional<Warning> forExpiry(UUID session, OffsetDateTime end) {
        return query("parking_session_id=? AND contract_end_at=? AND active=true", session, end).stream().findFirst();
    }
    public Warning insert(UUID session, UUID inspector, OffsetDateTime end, String observation, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO enforcement.verbal_warnings(id,parking_session_id,inspector_id,contract_end_at,observation,idempotency_key) VALUES(?,?,?,?,?,?)",
                id, session, inspector, end, observation, key);
        return query("id=?", id).getFirst();
    }
}
