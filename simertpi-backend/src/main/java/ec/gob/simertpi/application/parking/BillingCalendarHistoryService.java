package ec.gob.simertpi.application.parking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.api.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingCalendarHistoryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public BillingCalendarHistoryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void record(UUID calendarId, UUID previousCalendarId, String action, LocalDate effectiveFrom,
                       String reason, Map<String, ?> before, Map<String, ?> after) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            throw new IllegalStateException("ADMIN_ACTOR_REQUIRED_FOR_BILLING_CALENDAR_HISTORY");
        UUID actorId = jdbc.query("SELECT id FROM identity.users WHERE username=?", rs ->
                rs.next() ? rs.getObject(1, UUID.class) : null, auth.getName());
        if (actorId == null) throw new IllegalStateException("ADMIN_ACTOR_NOT_FOUND_FOR_BILLING_CALENDAR_HISTORY");
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new IllegalArgumentException("BILLING_CALENDAR_CHANGE_REASON_REQUIRED");
        jdbc.update("""
                INSERT INTO parking.billing_day_calendar_history
                  (id,calendar_id,previous_calendar_id,action,actor_id,occurred_at,effective_from,reason,before_state,after_state)
                VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """, UUID.randomUUID(), calendarId, previousCalendarId, action, actorId, effectiveFrom,
                reason.trim(), json(before), json(after));
    }

    @Transactional(readOnly = true)
    public List<BillingCalendarHistoryItem> history(UUID calendarId) {
        UUID groupId = jdbc.query("SELECT version_group_id FROM parking.billing_day_calendars WHERE id=?", rs ->
                rs.next() ? rs.getObject(1, UUID.class) : null, calendarId);
        if (groupId == null) throw new ResourceNotFoundException("Billing calendar not found");
        return jdbc.query("""
                SELECT h.id,h.calendar_id,h.previous_calendar_id,h.action,h.actor_id,u.username actor_name,
                       h.occurred_at,h.effective_from,h.reason,h.before_state::text,h.after_state::text
                  FROM parking.billing_day_calendar_history h
                  JOIN parking.billing_day_calendars c ON c.id=h.calendar_id
                  LEFT JOIN identity.users u ON u.id=h.actor_id
                 WHERE c.version_group_id=?
                 ORDER BY h.occurred_at DESC,h.id DESC
                """, (rs, n) -> new BillingCalendarHistoryItem(rs.getObject("id", UUID.class),
                rs.getObject("calendar_id", UUID.class), rs.getObject("previous_calendar_id", UUID.class),
                rs.getString("action"), rs.getObject("actor_id", UUID.class), rs.getString("actor_name"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getObject("effective_from", LocalDate.class),
                rs.getString("reason"), readJson(rs.getString("before_state")), readJson(rs.getString("after_state"))), groupId);
    }

    private String json(Map<String, ?> value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("BILLING_CALENDAR_HISTORY_SERIALIZATION_FAILED", failure); }
    }

    private JsonNode readJson(String value) {
        try { return value == null ? null : mapper.readTree(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("BILLING_CALENDAR_HISTORY_READ_FAILED", failure); }
    }

    public record BillingCalendarHistoryItem(UUID id, UUID calendarId, UUID previousCalendarId, String action,
            UUID actorId, String actorName, OffsetDateTime occurredAt, LocalDate effectiveFrom,
            String reason, JsonNode beforeState, JsonNode afterState) { }
}
