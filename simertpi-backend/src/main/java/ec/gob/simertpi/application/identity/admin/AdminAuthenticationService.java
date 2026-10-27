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
                        OffsetDateTime sessionExpiresAt, OffsetDateTime absoluteExpiresAt) {}
 public record Issued(String opaque, Identity identity) { @Override public String toString(){return "AdminSession[redacted]";} }
 private record Stored(UUID id, UUID userId, String stamp, OffsetDateTime absolute, OffsetDateTime idle, boolean revoked) {}
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
   if(!allowed(user))throw invalid();
   establish(user);
   if(validToken(previous))jdbc.update("UPDATE identity.admin_web_sessions SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE token_hash=?",hash(previous));
   OffsetDateTime now=now(),absolute=databasePrecision(now.plus(absoluteTtl)),idle=databasePrecision(now.plus(idleTtl));
   byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Arrays.fill(bytes,(byte)0);
   UUID id=UUID.randomUUID();
   jdbc.update("INSERT INTO identity.admin_web_sessions(id,user_id,token_hash,security_stamp,created_at,absolute_expires_at,idle_expires_at) VALUES (?,?,?,?,?,?,?)",id,user.getId(),hash(token),stamp(user),now,absolute,idle);
   jdbc.update("DELETE FROM identity.admin_login_throttles WHERE attempt_key=?",key);
   audit.success("ADMIN_LOGIN","ADMIN_SESSION",id,null,Map.of());
   return new Issued(token,identity(user,idle,absolute));
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
  OffsetDateTime now=now();
  boolean expired=!session.absolute().isAfter(now)||!session.idle().isAfter(now);
  boolean accessChanged=user==null||!allowed(user)||!stamp(user).equals(session.stamp());
  if(expired||accessChanged) {
   if(user!=null)establish(user);
   jdbc.update("UPDATE identity.admin_web_sessions SET revoked_at=? WHERE id=?",now,session.id());
   audit.recordOutcome("ADMIN_SESSION_REVOKED","ADMIN_SESSION",session.id(),"FAILURE",Map.of("reason","EXPIRED_OR_ACCESS_CHANGED"));
   if(expired)throw new AdminSessionExpiredAuthenticationException();
   throw invalid();
  }
  OffsetDateTime idle=databasePrecision(now.plus(idleTtl));if(idle.isAfter(session.absolute()))idle=session.absolute();
  jdbc.update("UPDATE identity.admin_web_sessions SET idle_expires_at=? WHERE id=?",idle,session.id());
  establish(user);
  return identity(user,idle,session.absolute());
 }
 @Transactional
 public void logout(String token) {
  Stored stored=find(token);
  if(stored==null||stored.revoked())return;
  users.findById(stored.userId()).flatMap(u->users.findByUsernameWithRoles(u.getUsername())).ifPresent(AdminAuthenticationService::establish);
  jdbc.update("UPDATE identity.admin_web_sessions SET revoked_at=CURRENT_TIMESTAMP WHERE id=?",stored.id());
  audit.success("ADMIN_LOGOUT","ADMIN_SESSION",stored.id(),null,Map.of());
 }
 private Stored find(String token) {
  if(!validToken(token))return null;
  var list=jdbc.query("SELECT id,user_id,security_stamp,absolute_expires_at,idle_expires_at,revoked_at IS NOT NULL AS revoked FROM identity.admin_web_sessions WHERE token_hash=? FOR UPDATE",
   (rs,n)->new Stored(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,OffsetDateTime.class),rs.getObject(5,OffsetDateTime.class),rs.getBoolean(6)),hash(token));
  return list.isEmpty()?null:list.getFirst();
 }
 private static boolean allowed(User user){return user.isEnabled()&&user.getRoles().stream().anyMatch(r->r.getCode().equals("SIMERTPI_ADMIN"));}
 private static String stamp(User user){return hash(user.getPasswordHash()+":"+user.getRoles().stream().map(r->r.getCode()).sorted().toList());}
 public static void establish(User user){var auth=UsernamePasswordAuthenticationToken.authenticated(user.getUsername(),null,user.getRoles().stream().map(r->new SimpleGrantedAuthority(r.getCode())).toList());SecurityContextHolder.getContext().setAuthentication(auth);}
 private static Identity identity(User u,OffsetDateTime idle,OffsetDateTime absolute){return new Identity(u.getId(),u.getUsername(),u.getRoles().stream().map(r->r.getCode()).sorted().toList(),u.isEnabled(),idle,absolute);}
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("Digest unavailable");}}
 public static boolean validToken(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{43}");}
 private static BadCredentialsException invalid(){return new BadCredentialsException("Authentication required");}
 private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
 private static OffsetDateTime databasePrecision(OffsetDateTime value){return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
}
