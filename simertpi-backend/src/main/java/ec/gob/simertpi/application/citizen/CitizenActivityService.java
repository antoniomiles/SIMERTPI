package ec.gob.simertpi.application.citizen;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Citizen-only projections: ownership never changes with the caller's staff roles. */
@Service
@Transactional(readOnly = true)
public class CitizenActivityService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    public CitizenActivityService(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc; this.users = users;
    }
    public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) { }
    public record Profile(String firstName, String lastName, String username, String email, String phone) { }
    public record Money(String amount, String currency) { }
    public record Extension(int minutes, OffsetDateTime approvedAt, OffsetDateTime expectedEndAt) { }
    public record History(UUID id, String plate, String zone, String street, String spaceCode,
                          OffsetDateTime startedAt, OffsetDateTime expectedEndAt, OffsetDateTime endedAt,
                          long contractedMinutes, Long occupiedMinutes, List<Money> paidAmounts, String status) { }
    public record HistoryDetail(History session, List<Extension> extensions) { }
    public record InboxItem(UUID id, String eventType, String referenceType, UUID referenceId,
                            String title, String message, OffsetDateTime createdAt, OffsetDateTime readAt) { }
    public record UnreadCount(long unreadCount) { }
    private UUID owner(String username) {
        return users.findByUsername(username).filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado")).getId();
    }
    public Profile profile(String username) {
        User user = users.findById(owner(username)).orElseThrow();
        return new Profile(user.getFirstName(), user.getLastName(), user.getUsername(), user.getEmail(), user.getPhone());
    }
    private static void pagination(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 1_000_000)
            throw new ec.gob.simertpi.api.InvalidRequestException("Paginación no válida");
    }
    private static <T> Page<T> page(List<T> rows, int limit, int offset) {
        boolean more = rows.size() > limit;
        return new Page<>(List.copyOf(rows.subList(0, Math.min(limit, rows.size()))), limit, offset, more);
    }
    private static final String HISTORY = """
        SELECT s.*,v.plate,p.code AS space_code,t.name AS street,z.name AS zone
        FROM parking.parking_sessions s JOIN identity.vehicles v ON v.id=s.vehicle_id
        JOIN parking.parking_spaces p ON p.id=s.parking_space_id
        JOIN parking.streets t ON t.id=p.street_id JOIN parking.zones z ON z.id=t.zone_id
        WHERE s.user_id=? AND s.status IN ('COMPLETED','CANCELLED')
        """;
    public Page<History> history(String username, int limit, int offset) {
        pagination(limit, offset);
        return page(jdbc.query(HISTORY + " ORDER BY s.started_at DESC,s.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> historyRow(rs), owner(username), limit + 1, offset), limit, offset);
    }
    public HistoryDetail historyDetail(String username, UUID id) {
        var rows = jdbc.query(HISTORY + " AND s.id=?", (rs, i) -> historyRow(rs), owner(username), id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Estacionamiento no encontrado");
        var extensions = jdbc.query("""
            SELECT e.additional_minutes,p.paid_at,e.new_expected_end_at FROM parking.session_extensions e
            JOIN payments.payments p ON p.id=e.payment_id
            WHERE e.parking_session_id=? AND e.status='APPROVED' AND p.status='APPROVED'
            ORDER BY e.created_at,e.id
            """, (rs, i) -> new Extension(rs.getInt(1), rs.getObject(2, OffsetDateTime.class),
                rs.getObject(3, OffsetDateTime.class)), id);
        return new HistoryDetail(rows.getFirst(), extensions);
    }
    private History historyRow(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        OffsetDateTime start = rs.getObject("started_at", OffsetDateTime.class);
        OffsetDateTime end = rs.getObject("expected_end_at", OffsetDateTime.class);
        OffsetDateTime closed = rs.getObject("ended_at", OffsetDateTime.class);
        // Payments, not attempts or session.total_amount, are the monetary authority.
        // Group by currency instead of silently adding incompatible currencies.
        List<Money> paid = jdbc.query("""
            SELECT currency,SUM(amount) AS paid FROM payments.payments
            WHERE parking_session_id=? AND status='APPROVED' GROUP BY currency ORDER BY currency
            """, (row, i) -> new Money(row.getBigDecimal("paid").setScale(2).toPlainString(), row.getString("currency")), id);
        return new History(id, rs.getString("plate"), rs.getString("zone"), rs.getString("street"),
                rs.getString("space_code"), start, end, closed, Duration.between(start, end).toMinutes(),
                closed == null ? null : Duration.between(start, closed).toMinutes(), paid, rs.getString("status"));
    }
    public Page<InboxItem> inbox(String username, int limit, int offset) {
        pagination(limit, offset);
        return page(jdbc.query("""
            SELECT id,event_type,reference_type,reference_id,title,message,created_at,read_at FROM notification.inbox_items WHERE user_id=?
            ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
            """, (rs, i) -> inboxRow(rs), owner(username), limit + 1, offset), limit, offset);
    }
    public InboxItem inboxDetail(String username, UUID id) {
        var rows = jdbc.query("SELECT id,event_type,reference_type,reference_id,title,message,created_at,read_at FROM notification.inbox_items WHERE user_id=? AND id=?",
                (rs, i) -> inboxRow(rs), owner(username), id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Notificación no encontrada");
        return rows.getFirst();
    }
    @Transactional
    public InboxItem read(String username, UUID id) {
        UUID user = owner(username);
        int count = jdbc.update("UPDATE notification.inbox_items SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE user_id=? AND id=?", user, id);
        if (count == 0) throw new ResourceNotFoundException("Notificación no encontrada");
        return inboxDetail(username, id);
    }
    public UnreadCount unread(String username) {
        return new UnreadCount(jdbc.queryForObject("SELECT count(*) FROM notification.inbox_items WHERE user_id=? AND read_at IS NULL", Long.class, owner(username)));
    }
    private InboxItem inboxRow(ResultSet rs) throws SQLException {
        return new InboxItem(rs.getObject("id", UUID.class), rs.getString("event_type"),
                rs.getString("reference_type"), rs.getObject("reference_id", UUID.class),
                rs.getString("title"), rs.getString("message"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("read_at", OffsetDateTime.class));
    }
}
