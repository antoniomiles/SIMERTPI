package ec.gob.simertpi.application.identity.admin;

import ec.gob.simertpi.api.identity.admin.AdminAccessController.Account;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.RoleSummary;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.UserPage;
import ec.gob.simertpi.application.audit.Audited;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdminAccessQueryService {
    private static final String FROM = "FROM identity.users u";
    private final NamedParameterJdbcTemplate jdbc;

    public AdminAccessQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Audited(action = "ADMIN_USERS_SEARCHED", resourceType = "USER_DIRECTORY")
    @Transactional(readOnly = true)
    public UserPage users(int page, int size, String query, String role, Boolean enabled) {
        StringBuilder predicate = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (query != null && !query.isBlank()) {
            predicate.append(" AND (u.username ILIKE :query OR u.email ILIKE :query")
                    .append(" OR u.first_name ILIKE :query OR u.last_name ILIKE :query")
                    .append(" OR (u.first_name || ' ' || u.last_name) ILIKE :query)");
            params.addValue("query", "%" + query.trim() + "%", Types.VARCHAR);
        }
        if (role != null && !role.isBlank()) {
            predicate.append(" AND EXISTS (SELECT 1 FROM identity.user_roles urf")
                    .append(" JOIN identity.roles rf ON rf.id=urf.role_id")
                    .append(" WHERE urf.user_id=u.id AND rf.code=:role)");
            params.addValue("role", role.trim().toUpperCase(Locale.ROOT), Types.VARCHAR);
        }
        if (enabled != null) {
            predicate.append(" AND u.enabled=:enabled");
            params.addValue("enabled", enabled, Types.BOOLEAN);
        }
        String where = predicate.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where, params, Long.class);
        List<Account> content = jdbc.query("""
                SELECT u.id, u.username, u.email, u.first_name, u.last_name, u.enabled, u.created_at,
                       (NOT u.enabled AND u.password_hash IS NULL AND EXISTS (
                           SELECT 1 FROM identity.user_roles pending_ur
                           JOIN identity.roles pending_r ON pending_r.id=pending_ur.role_id
                           WHERE pending_ur.user_id=u.id AND pending_r.code='INSPECTOR'
                       )) AS activation_pending,
                       COALESCE(array_agg(DISTINCT r.code ORDER BY r.code)
                           FILTER (WHERE r.code IS NOT NULL), ARRAY[]::varchar[]) AS role_codes
                """ + FROM + " LEFT JOIN identity.user_roles ur ON ur.user_id=u.id"
                + " LEFT JOIN identity.roles r ON r.id=ur.role_id" + where
                + " GROUP BY u.id ORDER BY u.created_at DESC, u.id LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource(params.getValues())
                        .addValue("limit", size)
                        .addValue("offset", (long) page * size),
                (rs, row) -> new Account(
                        rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                        rs.getString("first_name"), rs.getString("last_name"), arrayStrings(rs.getArray("role_codes")),
                        rs.getBoolean("enabled"), rs.getBoolean("activation_pending"),
                        rs.getObject("created_at", OffsetDateTime.class)));
        int totalPages = total == 0 ? 0 : Math.toIntExact(total / size + (total % size == 0 ? 0 : 1));
        return new UserPage(content, page, size, total, totalPages);
    }

    @Audited(action = "ADMIN_ROLES_VIEWED", resourceType = "ROLE_CATALOG")
    @Transactional(readOnly = true)
    public List<RoleSummary> roles() {
        return jdbc.query("""
                SELECT r.code, r.name, r.description, COUNT(ur.user_id) AS assigned_accounts
                FROM identity.roles r
                LEFT JOIN identity.user_roles ur ON ur.role_id=r.id
                GROUP BY r.id, r.code, r.name, r.description
                ORDER BY r.code
                """, (rs, row) -> new RoleSummary(rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getLong("assigned_accounts")));
    }

    private static List<String> arrayStrings(Array value) throws java.sql.SQLException {
        if (value == null) return List.of();
        Object raw = value.getArray();
        return raw instanceof Object[] values
                ? Arrays.stream(values).map(String::valueOf).toList()
                : List.of();
    }
}
