package ec.gob.simertpi.application.parking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TariffChangeHistoryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TariffChangeHistoryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void record(String action, Tariff tariff, UUID previousTariffId, OffsetDateTime effectiveAt,
                       String reason, Tariff before) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal()))
            throw new IllegalStateException("ADMIN_ACTOR_REQUIRED_FOR_TARIFF_HISTORY");
        UUID actorId = jdbc.query("SELECT id FROM identity.users WHERE username=?", rs ->
                rs.next() ? rs.getObject(1, UUID.class) : null, authentication.getName());
        if (actorId == null) throw new IllegalStateException("ADMIN_ACTOR_NOT_FOUND_FOR_TARIFF_HISTORY");
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new IllegalArgumentException("TARIFF_CHANGE_REASON_REQUIRED");
        jdbc.update("""
                INSERT INTO parking.tariff_change_history
                  (id,tariff_id,previous_tariff_id,action,actor_id,occurred_at,effective_at,reason,before_state,after_state)
                VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """, UUID.randomUUID(), tariff.getId(), previousTariffId, action, actorId,
                effectiveAt, reason.trim(), before == null ? null : json(before), json(tariff));
    }

    @Transactional(readOnly = true)
    public List<TariffHistoryItem> history(UUID tariffId) {
        UUID groupId = jdbc.query("SELECT version_group_id FROM parking.tariffs WHERE id=?", rs ->
                rs.next() ? rs.getObject(1, UUID.class) : null, tariffId);
        if (groupId == null) throw new ResourceNotFoundException("Tariff not found");
        return jdbc.query("""
                SELECT h.id,h.tariff_id,h.previous_tariff_id,h.action,h.actor_id,u.username AS actor_name,
                       h.occurred_at,h.effective_at,h.reason,h.before_state::text,h.after_state::text
                  FROM parking.tariff_change_history h
                  JOIN parking.tariffs t ON t.id=h.tariff_id
                  LEFT JOIN identity.users u ON u.id=h.actor_id
                 WHERE t.version_group_id=?
                 ORDER BY h.occurred_at DESC,h.id DESC
                """, (rs, rowNum) -> new TariffHistoryItem(
                rs.getObject("id", UUID.class), rs.getObject("tariff_id", UUID.class),
                rs.getObject("previous_tariff_id", UUID.class), rs.getString("action"),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_name"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("effective_at", OffsetDateTime.class),
                rs.getString("reason"), readJson(rs.getString("before_state")), readJson(rs.getString("after_state"))), groupId);
    }

    @Transactional(readOnly = true)
    public TariffHistoryPage historyPage(UUID tariffId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("INVALID_TARIFF_HISTORY_PAGE");
        UUID groupId = null;
        if (tariffId != null) {
            groupId = jdbc.query("SELECT version_group_id FROM parking.tariffs WHERE id=?", rs ->
                    rs.next() ? rs.getObject(1, UUID.class) : null, tariffId);
            if (groupId == null) throw new ResourceNotFoundException("Tariff not found");
        }
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM parking.tariff_change_history h
                  JOIN parking.tariffs t ON t.id=h.tariff_id
                 WHERE (?::uuid IS NULL OR t.version_group_id=?)
                """, Long.class, groupId, groupId);
        List<TariffHistoryItem> items = jdbc.query("""
                SELECT h.id,h.tariff_id,h.previous_tariff_id,h.action,h.actor_id,u.username AS actor_name,
                       h.occurred_at,h.effective_at,h.reason,h.before_state::text,h.after_state::text
                  FROM parking.tariff_change_history h
                  JOIN parking.tariffs t ON t.id=h.tariff_id
                  LEFT JOIN identity.users u ON u.id=h.actor_id
                 WHERE (?::uuid IS NULL OR t.version_group_id=?)
                 ORDER BY h.occurred_at DESC,h.id DESC LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new TariffHistoryItem(
                rs.getObject("id", UUID.class), rs.getObject("tariff_id", UUID.class),
                rs.getObject("previous_tariff_id", UUID.class), rs.getString("action"),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_name"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("effective_at", OffsetDateTime.class),
                rs.getString("reason"), readJson(rs.getString("before_state")), readJson(rs.getString("after_state"))),
                groupId, groupId, size, (long) page * size);
        return new TariffHistoryPage(items, page, size, total == null ? 0 : total);
    }

    private String json(Tariff tariff) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", tariff.getId()); snapshot.put("versionGroupId", tariff.getVersionGroupId());
        snapshot.put("previousVersionId", tariff.getPreviousVersionId()); snapshot.put("rowVersion", tariff.getRowVersion());
        snapshot.put("code", tariff.getCode()); snapshot.put("name", tariff.getName()); snapshot.put("zoneId", tariff.getZoneId());
        snapshot.put("currency", tariff.getCurrency()); snapshot.put("amount", tariff.getAmount());
        snapshot.put("durationMinutes", tariff.getDurationMinutes()); snapshot.put("minMinutes", tariff.getMinMinutes());
        snapshot.put("maxContinuousMinutes", tariff.getMaxContinuousMinutes());
        snapshot.put("gracePeriodMinutes", tariff.getGracePeriodMinutes()); snapshot.put("roundingMode", tariff.getRoundingMode());
        snapshot.put("validFrom", tariff.getValidFrom()); snapshot.put("validTo", tariff.getValidTo());
        snapshot.put("normativeReference", tariff.getNormativeReference()); snapshot.put("active", tariff.isActive());
        snapshot.put("cancelledAt", tariff.getCancelledAt()); snapshot.put("cancelledByUserId", tariff.getCancelledByUserId());
        snapshot.put("cancellationReason", tariff.getCancellationReason());
        try { return mapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("TARIFF_HISTORY_SNAPSHOT_SERIALIZATION_FAILED", failure); }
    }

    private JsonNode readJson(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("TARIFF_HISTORY_SNAPSHOT_READ_FAILED", failure); }
    }

    public record TariffHistoryItem(UUID id, UUID tariffId, UUID previousTariffId, String action,
            UUID actorId, String actorName, OffsetDateTime occurredAt, OffsetDateTime effectiveAt,
            String reason, JsonNode beforeState, JsonNode afterState) { }
    public record TariffHistoryPage(List<TariffHistoryItem> items, int page, int size, long total) { }
}
