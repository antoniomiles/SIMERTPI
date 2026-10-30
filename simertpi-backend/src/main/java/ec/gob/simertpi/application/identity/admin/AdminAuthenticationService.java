package ec.gob.simertpi.application.identity.admin;

import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.config.SimertpiUserDetailsService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuthenticationService {
 public static final String COOKIE = "__Host-SIMERTPI_ADMIN_SESSION";
 public record Identity(UUID userId, String username, List<String> roles, boolean enabled,
                        OffsetDateTime sessionExpiresAt, OffsetDateTime absoluteExpiresAt, List<String> permissions) {
  public Identity(UUID userId,String username,List<String> roles,boolean enabled,OffsetDateTime sessionExpiresAt,OffsetDateTime absoluteExpiresAt) {
   this(userId,username,roles,enabled,sessionExpiresAt,absoluteExpiresAt,List.of());
  }
 }
 public record Issued(String opaque, Identity identity) { @Override public String toString(){return "AdminSession[redacted]";} }
 private record Stored(UUID id, UUID userId, String stamp, OffsetDateTime absolute, OffsetDateTime idle, boolean revoked) {}
 private record AuthorizationSnapshot(List<String> roles,List<String> permissions,List<String> roleState,boolean portalEnabled) {}
 private final JdbcTemplate jdbc; private final UserRepository users; private final AuditService audit;
 private final DaoAuthenticationProvider provider; private final Duration idleTtl, absoluteTtl;
 private final SecureRandom random = new SecureRandom();
 public AdminAuthenticationService(JdbcTemplate jdbc,UserRepository users,AuditService audit,
   SimertpiUserDetailsService details,PasswordEncoder encoder,
   @Value("${simertpi.admin-auth.idle-ttl:15m}") Duration idleTtl,
   @Value("${simertpi.admin-auth.absolute-ttl:8h}") Duration absoluteTtl) {
  if(idleTtl.isNegative()||idleTtl.isZero()||absoluteTtl.compareTo(idleTtl)<0)throw new IllegalArgumentException("Invalid administrative session lifetime");
  this.jdbc=jdbc;this.users=users;this.audit=audit;this.idleTtl=idleTtl;this.absoluteTtl=absoluteTtl;
  provider=new DaoAuthenticationProvider(details);provider.setPasswordEncoder(encoder);
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public Issued login(String username,String password,String previous,String source) {
  String key=hash(source+":"+username.toLowerCase(Locale.ROOT));
  int attempts=jdbc.queryForObject("""
   INSERT INTO identity.admin_login_throttles(attempt_key,window_started_at,attempts) VALUES (?,CURRENT_TIMESTAMP,1)
   ON CONFLICT(attempt_key) DO UPDATE SET
     attempts=CASE WHEN identity.admin_login_throttles.window_started_at<CURRENT_TIMESTAMP-INTERVAL '5 minutes' THEN 1 ELSE identity.admin_login_throttles.attempts+1 END,
     window_started_at=CASE WHEN identity.admin_login_throttles.window_started_at<CURRENT_TIMESTAMP-INTERVAL '5 minutes' THEN CURRENT_TIMESTAMP ELSE identity.admin_login_throttles.window_started_at END
   RETURNING attempts
   """,Integer.class,key);
  try {
   if(attempts>10)throw new LockedException("Authentication required");
   if(password==null||password.getBytes(StandardCharsets.UTF_8).length>72)throw invalid();
   provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username,password));
   User user=users.findByUsernameWithRoles(username).orElseThrow(AdminAuthenticationService::invalid);
   AuthorizationSnapshot snapshot=authorizationSnapshot(user.getId());
   if(!allowed(user,snapshot))throw invalid();
   establish(user,snapshot);
   if(validToken(previous))jdbc.update("UPDATE identity.admin_web_sessions SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE token_hash=?",hash(previous));
   OffsetDateTime now=now(),absolute=databasePrecision(now.plus(absoluteTtl)),idle=databasePrecision(now.plus(idleTtl));
   byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Arrays.fill(bytes,(byte)0);
   UUID id=UUID.randomUUID();
   jdbc.update("INSERT INTO identity.admin_web_sessions(id,user_id,token_hash,security_stamp,created_at,absolute_expires_at,idle_expires_at) VALUES (?,?,?,?,?,?,?)",id,user.getId(),hash(token),stamp(user,snapshot),now,absolute,idle);
   jdbc.update("DELETE FROM identity.admin_login_throttles WHERE attempt_key=?",key);
   audit.success("ADMIN_LOGIN","ADMIN_SESSION",id,null,Map.of());
   return new Issued(token,identity(user,snapshot,idle,absolute));
  } catch(AuthenticationException failure) {
   audit.recordOutcome("ADMIN_LOGIN","ADMIN_SESSION",null,"FAILURE",Map.of("reason",attempts>10?"THROTTLED":"REJECTED"));
   throw invalid();
  }
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public Identity authenticate(String token) {
  Stored session=find(token);
  if(session==null||session.revoked())throw invalid();
  User user=users.findById(session.userId()).flatMap(u->users.findByUsernameWithRoles(u.getUsername())).orElse(null);
  AuthorizationSnapshot snapshot=user==null?new AuthorizationSnapshot(List.of(),List.of(),List.of(),false):authorizationSnapshot(user.getId());
  OffsetDateTime now=now();
  boolean expired=!session.absolute().isAfter(now)||!session.idle().isAfter(now);
  boolean accessChanged=user==null||!allowed(user,snapshot)||!stamp(user,snapshot).equals(session.stamp());
  if(expired||accessChanged) {
   if(user!=null)establish(user,snapshot);
   jdbc.update("UPDATE identity.admin_web_sessions SET revoked_at=? WHERE id=?",now,session.id());
   audit.recordOutcome("ADMIN_SESSION_REVOKED","ADMIN_SESSION",session.id(),"FAILURE",Map.of("reason","EXPIRED_OR_ACCESS_CHANGED"));
   if(expired)throw new AdminSessionExpiredAuthenticationException();
   throw invalid();
  }
  OffsetDateTime idle=databasePrecision(now.plus(idleTtl));if(idle.isAfter(session.absolute()))idle=session.absolute();
  jdbc.update("UPDATE identity.admin_web_sessions SET idle_expires_at=? WHERE id=?",idle,session.id());
  establish(user,snapshot);
  return identity(user,snapshot,idle,session.absolute());
 }
 @Transactional
 public void logout(String token) {
  Stored stored=find(token);
  if(stored==null||stored.revoked())return;
  users.findById(stored.userId()).flatMap(u->users.findByUsernameWithRoles(u.getUsername())).ifPresent(user -> establish(user,authorizationSnapshot(user.getId())));
  jdbc.update("UPDATE identity.admin_web_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE id=?",stored.id());
  audit.success("ADMIN_LOGOUT","ADMIN_SESSION",stored.id(),null,Map.of());
 }
 private Stored find(String token) {
  if(!validToken(token))return null;
  var list=jdbc.query("SELECT id,user_id,security_stamp,absolute_expires_at,idle_expires_at,revoked_at IS NOT NULL AS revoked FROM identity.admin_web_sessions WHERE token_hash=? FOR UPDATE",
   (rs,n)->new Stored(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,OffsetDateTime.class),rs.getObject(5,OffsetDateTime.class),rs.getBoolean(6)),hash(token));
  return list.isEmpty()?null:list.getFirst();
 }
 private AuthorizationSnapshot authorizationSnapshot(UUID userId) {
  record RoleGrant(String code,boolean enabled,boolean portal,long version,String permission) { }
  List<RoleGrant> grants=jdbc.query("""
   SELECT r.code,r.enabled,r.portal_enabled,r.row_version,p.code AS permission_code
     FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id
     LEFT JOIN identity.role_permissions rp ON rp.role_id=r.id
     LEFT JOIN identity.permissions p ON p.code=rp.permission_code AND p.assignable=true
    WHERE ur.user_id=? ORDER BY r.code,p.code
   """,(rs,n)->new RoleGrant(rs.getString("code"),rs.getBoolean("enabled"),rs.getBoolean("portal_enabled"),
       rs.getLong("row_version"),rs.getString("permission_code")),userId);
  List<String> roles=grants.stream().filter(RoleGrant::enabled).map(RoleGrant::code).distinct().sorted().toList();
  TreeSet<String> effectivePermissions=new TreeSet<>();
  grants.stream().filter(RoleGrant::enabled).map(RoleGrant::permission).filter(Objects::nonNull).forEach(effectivePermissions::add);
  // Built-in portal roles remain compatible with V50 and do not depend on editable grants.
  if(roles.contains("IT_ADMIN")||roles.contains("SIMERTPI_ADMIN"))
   effectivePermissions.addAll(ec.gob.simertpi.application.identity.admin.authorization.AdminPermissionPolicy.supportedPermissionCodes());
  if(roles.contains("IT_ADMIN")||roles.contains("SIMERTPI_ADMIN")) effectivePermissions.add("reports.financial.view");
  List<String> permissions=List.copyOf(effectivePermissions);
  List<String> roleState=grants.stream().map(g->g.code()+":"+g.enabled()+":"+g.portal()+":"+g.version()+":"+
      Objects.toString(g.permission(),"-")).toList();
  boolean portalEnabled=grants.stream().anyMatch(g->g.enabled()&&g.portal());
  return new AuthorizationSnapshot(roles,permissions,roleState,portalEnabled);
 }
 private static boolean allowed(User user,AuthorizationSnapshot snapshot){
  boolean protectedAdmin=snapshot.roles().stream().anyMatch(role->Set.of("SIMERTPI_ADMIN","IT_ADMIN").contains(role));
  return user.isEnabled()&&(protectedAdmin||(snapshot.portalEnabled()&&!snapshot.permissions().isEmpty()));
 }
 private static String stamp(User user,AuthorizationSnapshot snapshot){
  return hash(user.getPasswordHash()+":"+snapshot.roles()+":"+
      snapshot.roleState()+":"+snapshot.permissions());
 }
 private static void establish(User user,AuthorizationSnapshot snapshot){
  List<SimpleGrantedAuthority> authorities=new ArrayList<>();
  snapshot.roles().stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
  snapshot.permissions().stream().map(code->new SimpleGrantedAuthority("PERM:"+code)).forEach(authorities::add);
  if(allowed(user,snapshot))authorities.add(new SimpleGrantedAuthority("ADMIN_PORTAL"));
  var auth=UsernamePasswordAuthenticationToken.authenticated(user.getUsername(),null,authorities);
  SecurityContextHolder.getContext().setAuthentication(auth);
 }
 private static Identity identity(User u,AuthorizationSnapshot snapshot,OffsetDateTime idle,OffsetDateTime absolute){
  return new Identity(u.getId(),u.getUsername(),snapshot.roles(),u.isEnabled(),idle,absolute,snapshot.permissions());
 }
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("Digest unavailable");}}
 public static boolean validToken(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{43}");}
 private static BadCredentialsException invalid(){return new BadCredentialsException("Authentication required");}
 private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
 private static OffsetDateTime databasePrecision(OffsetDateTime value){return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
}
