package ec.gob.simertpi.application.identity.admin;

import ec.gob.simertpi.api.identity.admin.AdminAccessController.Account;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.RoleSummary;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.UserPage;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.PermissionMatrix;
import ec.gob.simertpi.api.identity.admin.AdminAccessController.RolePermission;
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
    public UserPage users(int page, int size, String query, String role, Boolean enabled, String actorUsername) {
        List<String> actorRoles = actorRoles(actorUsername);
        boolean itAdmin = actorRoles.contains("IT_ADMIN");
        if (!itAdmin && !actorRoles.contains("SIMERTPI_ADMIN")) throw new org.springframework.security.access.AccessDeniedException("Acceso denegado.");
        StringBuilder predicate = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!itAdmin) {
            predicate.append(" AND EXISTS (SELECT 1 FROM identity.user_roles scope_ur JOIN identity.roles scope_r ON scope_r.id=scope_ur.role_id WHERE scope_ur.user_id=u.id AND scope_r.code='INSPECTOR')")
                    .append(" AND NOT EXISTS (SELECT 1 FROM identity.user_roles private_ur JOIN identity.roles private_r ON private_r.id=private_ur.role_id WHERE private_ur.user_id=u.id AND private_r.code IN ('IT_ADMIN','SIMERTPI_ADMIN'))");
        }
        if (query != null && !query.isBlank()) {
            predicate.append(" AND (u.username ILIKE :query OR u.email ILIKE :query")
                    .append(" OR u.first_name ILIKE :query OR u.last_name ILIKE :query")
                    .append(" OR (u.first_name || ' ' || u.last_name) ILIKE :query)");
            params.addValue("query", "%" + query.trim() + "%", Types.VARCHAR);
        }
        if (role != null && !role.isBlank()) {
            if (!itAdmin && !"INSPECTOR".equalsIgnoreCase(role.trim())) {
                return new UserPage(List.of(), page, size, 0, 0);
            }
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
                SELECT u.id, u.username, u.email, u.first_name, u.last_name, u.phone, u.enabled, u.created_at, u.updated_at,
                       (NOT u.enabled AND u.password_hash IS NULL AND EXISTS (
                           SELECT 1 FROM identity.inspector_activation_tokens token WHERE token.user_id=u.id
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
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"), arrayStrings(rs.getArray("role_codes")),
                        rs.getBoolean("enabled"), rs.getBoolean("activation_pending"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)));
        int totalPages = total == 0 ? 0 : Math.toIntExact(total / size + (total % size == 0 ? 0 : 1));
        return new UserPage(content, page, size, total, totalPages);
    }

    @Audited(action = "ADMIN_ROLES_VIEWED", resourceType = "ROLE_CATALOG")
    @Transactional(readOnly = true)
    public List<RoleSummary> roles(String actorUsername) {
        requireItAdmin(actorUsername);
        return jdbc.query("""
                SELECT r.code, r.name, r.description, COUNT(ur.user_id) AS assigned_accounts
                FROM identity.roles r
                LEFT JOIN identity.user_roles ur ON ur.role_id=r.id
                GROUP BY r.id, r.code, r.name, r.description
                ORDER BY r.code
                """, (rs, row) -> new RoleSummary(rs.getString("code"), rs.getString("name"),
                displayName(rs.getString("code"), rs.getString("name")), rs.getString("description"), rs.getLong("assigned_accounts")));
    }

    @Audited(action = "ADMIN_PERMISSIONS_VIEWED", resourceType = "ROLE_PERMISSION_MATRIX")
    @Transactional(readOnly = true)
    public PermissionMatrix permissions(String actorUsername) {
        requireItAdmin(actorUsername);
        List<RolePermission> matrix = jdbc.query("SELECT code,name FROM identity.roles ORDER BY code", (rs, row) -> {
            String code = rs.getString("code");
            List<String> capabilities = switch (code) {
                case "IT_ADMIN" -> List.of("USERS_READ", "USERS_MANAGE_ALL", "ROLES_ASSIGN", "CONFIGURATION_MANAGE", "INSPECTIONS_REVIEW", "AUDIT_READ");
                case "SIMERTPI_ADMIN" -> List.of("USERS_READ", "USERS_MANAGE_INSPECTORS", "CONFIGURATION_MANAGE", "INSPECTIONS_REVIEW", "AUDIT_READ");
                case "INSPECTOR" -> List.of("INSPECTIONS_CREATE", "EVIDENCE_CREATE");
                case "AUDITOR" -> List.of("AUDIT_READ");
                default -> List.of();
            };
            return new RolePermission(code, displayName(code, rs.getString("name")), capabilities);
        });
        return new PermissionMatrix(matrix);
    }

    private List<String> actorRoles(String username) {
        List<String> roles = jdbc.query("""
                SELECT r.code FROM identity.users u JOIN identity.user_roles ur ON ur.user_id=u.id
                JOIN identity.roles r ON r.id=ur.role_id WHERE u.username=:username AND u.enabled=TRUE ORDER BY r.code
                """, new MapSqlParameterSource("username", username), (rs, row) -> rs.getString(1));
        if (roles.isEmpty()) throw new org.springframework.security.access.AccessDeniedException("Acceso denegado.");
        return roles;
    }

    private void requireItAdmin(String username) {
        List<String> roles = actorRoles(username);
        if (!roles.contains("IT_ADMIN")) {
            throw new org.springframework.security.access.AccessDeniedException("Acceso denegado.");
        }
    }

    private static String displayName(String code, String persisted) {
        return switch (code) {
            case "IT_ADMIN" -> "Superadministrador";
            case "SIMERTPI_ADMIN" -> "Administrador";
            default -> persisted;
        };
    }

    private static List<String> arrayStrings(Array value) throws java.sql.SQLException {
        if (value == null) return List.of();
        Object raw = value.getArray();
        return raw instanceof Object[] values
                ? Arrays.stream(values).map(String::valueOf).toList()
                : List.of();
    }
}
