package ec.gob.simertpi.application.identity.admin;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** CRUD for custom roles only. Built-in roles and system permissions are immutable. */
@Service
public class AdminRoleManagementService {
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public AdminRoleManagementService(JdbcTemplate jdbc, AuditService audit) { this.jdbc=jdbc; this.audit=audit; }

    public record Permission(String code,String moduleCode,String actionCode,String name,String description,boolean assignable) { }
    public record Role(String code,String name,String description,boolean enabled,boolean protectedRole,boolean portalEnabled,
                       long rowVersion,long userCount,List<String> permissions,OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
    public record History(UUID id,String action,String actor,OffsetDateTime occurredAt,String result,Map<String,Object> metadata) { }
    public record CreateRequest(String code,String name,String description,List<String> permissions) { }
    public record UpdateRequest(String name,String description,List<String> permissions,OffsetDateTime updatedAt,long rowVersion,String reason) { }
    public record StatusRequest(boolean enabled,OffsetDateTime updatedAt,long rowVersion,String reason) { }

    @Transactional
    public List<Permission> catalog(String actor) {
        requireSuperAdmin(actor);
        return jdbc.query("SELECT code,module_code,action_code,name,description,assignable FROM identity.permissions ORDER BY module_code,action_code,code",
          (rs,n)->new Permission(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getBoolean(6)));
    }
    @Transactional
    public List<Role> roles(String actor) {
        requireSuperAdmin(actor);
        List<Role> roles = jdbc.query("""
          SELECT r.code,r.name,r.description,r.enabled,r.is_system,r.portal_enabled,r.row_version,COUNT(DISTINCT ur.user_id) AS users,
                 COALESCE(array_agg(DISTINCT rp.permission_code ORDER BY rp.permission_code) FILTER(WHERE rp.permission_code IS NOT NULL),ARRAY[]::varchar[]) AS grants,
                 r.created_at,r.updated_at FROM identity.roles r LEFT JOIN identity.user_roles ur ON ur.role_id=r.id
          LEFT JOIN identity.role_permissions rp ON rp.role_id=r.id GROUP BY r.id ORDER BY r.is_system DESC,r.display_name,r.code
          """,(rs,n)->mapRole(rs));
        audit.success("ADMIN_ROLES_VIEWED", "ACCESS_ROLES", null, null, Map.of("count", roles.size()));
        return roles;
    }
    @Transactional
    public Role get(String code,String actor) { requireSuperAdmin(actor); return role(normalize(code)); }

    @Transactional
    public Role create(CreateRequest request,String actor) {
        UUID actorId=requireSuperAdmin(actor); String code=normalize(request.code());
        String name=required(request.name(),100,"El nombre del rol es obligatorio."); String description=optional(request.description(),255);
        List<String> grants=validatePermissions(request.permissions());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('role:' || ?,0))",rs->null,code);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.roles WHERE code=?)",Boolean.class,code)))
            throw new IllegalArgumentException("Ya existe un rol con ese identificador.");
        UUID id=UUID.randomUUID(); OffsetDateTime now=OffsetDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("""
          INSERT INTO identity.roles(id,code,name,description,display_name,enabled,portal_enabled,is_system,row_version,created_at,updated_at,created_by,updated_by)
          VALUES (?,?,?,?,?,TRUE,?,FALSE,0,?,?,?,?)
          """,id,code,name,description,name,!grants.isEmpty(),now,now,actorId,actorId);
        insertGrants(id,grants,actorId);
        audit.success("ADMIN_ROLE_CREATED","ACCESS_ROLE",id,null,Map.of("code",code,"name",name,"permissions",grants));
        return role(code);
    }

    @Transactional
    public Role update(String codeValue,UpdateRequest request,String actor) {
        UUID actorId=requireSuperAdmin(actor); String code=normalize(codeValue); Role current=lockRole(code);
        if(current.protectedRole()) throw new AccessDeniedException("Los roles protegidos del sistema no se pueden editar.");
        checkVersion(current,request.updatedAt(),request.rowVersion());
        String name=required(request.name(),100,"El nombre del rol es obligatorio."); String description=optional(request.description(),255);
        String reason=required(request.reason(),500,"El motivo es obligatorio."); List<String> grants=validatePermissions(request.permissions());
        OffsetDateTime now=nextVersionAt(current.updatedAt());
        int updated=jdbc.update("""
          UPDATE identity.roles SET name=?,display_name=?,description=?,portal_enabled=?,updated_at=?,updated_by=?,row_version=row_version+1
          WHERE code=? AND is_system=FALSE AND row_version=?
          """,name,name,description,!grants.isEmpty(),now,actorId,code,current.rowVersion());
        if(updated!=1) throw conflict();
        UUID id=roleId(code); jdbc.update("DELETE FROM identity.role_permissions WHERE role_id=?",id); insertGrants(id,grants,actorId);
        audit.success("ADMIN_ROLE_UPDATED","ACCESS_ROLE",id,null,Map.of("code",code,"reason",reason,
          "before.name",current.name(),"after.name",name,"before.permissions",current.permissions(),"after.permissions",grants));
        return role(code);
    }

    @Transactional
    public Role setEnabled(String codeValue,StatusRequest request,String actor) {
        UUID actorId=requireSuperAdmin(actor); String code=normalize(codeValue); Role current=lockRole(code);
        if(current.protectedRole()) throw new AccessDeniedException("Los roles protegidos del sistema no se pueden desactivar.");
        checkVersion(current,request.updatedAt(),request.rowVersion()); String reason=required(request.reason(),500,"El motivo es obligatorio.");
        if(!request.enabled()&&current.enabled()) {
            Integer activeUsers=jdbc.queryForObject("""
              SELECT COUNT(DISTINCT u.id) FROM identity.users u JOIN identity.user_roles ur ON ur.user_id=u.id
              WHERE ur.role_id=(SELECT id FROM identity.roles WHERE code=?) AND u.enabled
              """,Integer.class,code);
            if(activeUsers!=null&&activeUsers>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"No se puede desactivar el rol mientras tenga usuarios habilitados asignados.");
        }
        if(current.enabled()!=request.enabled()) {
            int updated=jdbc.update("UPDATE identity.roles SET enabled=?,updated_at=?,updated_by=?,row_version=row_version+1 WHERE code=? AND is_system=FALSE AND row_version=?",
                request.enabled(),nextVersionAt(current.updatedAt()),actorId,code,current.rowVersion());
            if(updated!=1) throw conflict();
        }
        UUID id=roleId(code);
        audit.success(request.enabled()?"ADMIN_ROLE_ENABLED":"ADMIN_ROLE_DISABLED","ACCESS_ROLE",id,null,
          Map.of("code",code,"reason",reason,"before.enabled",current.enabled(),"after.enabled",request.enabled()));
        return role(code);
    }

    @Transactional
    public List<History> history(String codeValue,String actor) {
        requireSuperAdmin(actor); UUID id=roleId(normalize(codeValue));
        return jdbc.query("""
          SELECT id,action,actor_name,occurred_at,result,metadata::text AS metadata FROM audit.functional_audit_log
          WHERE resource_type='ACCESS_ROLE' AND resource_id=? ORDER BY occurred_at DESC,id DESC LIMIT 100
          """,(rs,n)->new History(rs.getObject("id",UUID.class),rs.getString("action"),rs.getString("actor_name"),
            rs.getObject("occurred_at",OffsetDateTime.class),rs.getString("result"),json(rs.getString("metadata"))),id);
    }

    private Role lockRole(String code) {
        List<UUID> locked=jdbc.query("SELECT id FROM identity.roles WHERE code=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),code);
        if(locked.isEmpty()) throw new ResourceNotFoundException("No se encontró el rol.");
        return role(code);
    }
    private Role role(String code) {
        List<Role> rows=jdbc.query("""
          SELECT r.code,r.name,r.description,r.enabled,r.is_system,r.portal_enabled,r.row_version,
                 (SELECT COUNT(*) FROM identity.user_roles ur WHERE ur.role_id=r.id) AS users,
                 COALESCE(array_agg(DISTINCT rp.permission_code ORDER BY rp.permission_code) FILTER(WHERE rp.permission_code IS NOT NULL),ARRAY[]::varchar[]) AS grants,
                 r.created_at,r.updated_at FROM identity.roles r LEFT JOIN identity.role_permissions rp ON rp.role_id=r.id
          WHERE r.code=? GROUP BY r.id
          """,(rs,n)->mapRole(rs),code);
        if(rows.isEmpty()) throw new ResourceNotFoundException("No se encontró el rol."); return rows.getFirst();
    }
    private static Role mapRole(java.sql.ResultSet rs)throws java.sql.SQLException {
        java.sql.Array a=rs.getArray("grants"); Object[] v=a==null?new Object[0]:(Object[])a.getArray();
        return new Role(rs.getString("code"),rs.getString("name"),rs.getString("description"),rs.getBoolean("enabled"),rs.getBoolean("is_system"),
          rs.getBoolean("portal_enabled"),rs.getLong("row_version"),rs.getLong("users"),Arrays.stream(v).map(String::valueOf).toList(),
          rs.getObject("created_at",OffsetDateTime.class),rs.getObject("updated_at",OffsetDateTime.class));
    }
    private List<String> validatePermissions(List<String> requested) {
        if(requested==null||requested.size()>100) throw new IllegalArgumentException("La lista de permisos no es válida.");
        List<String> codes=requested.stream().map(s->s==null?"":s.trim()).sorted().toList();
        if(codes.stream().anyMatch(String::isBlank)||new HashSet<>(codes).size()!=codes.size()) throw new IllegalArgumentException("Los permisos no pueden estar vacíos ni repetidos.");
        if(codes.contains("reports.financial.view")&&!codes.contains("reports.view"))
            throw new IllegalArgumentException("Para consultar información financiera también debe habilitarse Reportes municipales.");
        if(codes.isEmpty()) return List.of(); String marks=String.join(",",Collections.nCopies(codes.size(),"?"));
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM identity.permissions WHERE assignable=TRUE AND code IN ("+marks+")",Integer.class,codes.toArray());
        if(count==null||count!=codes.size()) throw new IllegalArgumentException("Uno o más permisos no existen o están reservados para el Superadministrador."); return codes;
    }
    private void insertGrants(UUID id,List<String> codes,UUID actor) { for(String code:codes) jdbc.update("INSERT INTO identity.role_permissions(role_id,permission_code,assigned_by) VALUES (?,?,?)",id,code,actor); }
    private UUID requireSuperAdmin(String username) {
        List<UUID> ids=jdbc.query("""
          SELECT u.id FROM identity.users u JOIN identity.user_roles ur ON ur.user_id=u.id JOIN identity.roles r ON r.id=ur.role_id
          WHERE u.username=? AND u.enabled=TRUE AND r.code='IT_ADMIN' AND r.enabled=TRUE FOR SHARE OF u
          """,(rs,n)->rs.getObject(1,UUID.class),username);
        if(ids.isEmpty()) throw new AccessDeniedException("Solo el Superadministrador puede administrar roles y permisos."); return ids.getFirst();
    }
    private UUID roleId(String code) { UUID id=jdbc.query("SELECT id FROM identity.roles WHERE code=?",rs->rs.next()?rs.getObject(1,UUID.class):null,code); if(id==null) throw new ResourceNotFoundException("No se encontró el rol."); return id; }
    private static void checkVersion(Role role,OffsetDateTime updatedAt,long rowVersion) { if(updatedAt==null||!role.updatedAt().isEqual(updatedAt)||role.rowVersion()!=rowVersion) throw conflict(); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"El rol cambió desde que se consultó. Actualiza la información."); }
    private static OffsetDateTime nextVersionAt(OffsetDateTime old) { return OffsetDateTime.now(java.time.ZoneOffset.UTC).isAfter(old)?OffsetDateTime.now(java.time.ZoneOffset.UTC):old.plusNanos(1000); }
    private static String normalize(String s) { String c=s==null?"":s.trim().toUpperCase(Locale.ROOT); if(!CODE.matcher(c).matches()) throw new IllegalArgumentException("El código del rol no tiene un formato válido."); return c; }
    private static String required(String v,int max,String message) { String s=v==null?"":v.trim(); if(s.isEmpty()||s.length()>max) throw new IllegalArgumentException(message); return s; }
    private static String optional(String v,int max) { String s=v==null?"":v.trim(); if(s.length()>max) throw new IllegalArgumentException("El texto supera la longitud permitida."); return s.isEmpty()?null:s; }
    private static Map<String,Object> json(String v) { try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(v,new com.fasterxml.jackson.core.type.TypeReference<>(){}); } catch(Exception e) { return Map.of(); } }
}
