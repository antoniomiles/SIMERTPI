package ec.gob.simertpi.application.identity.auth;

import ec.gob.simertpi.config.SimertpiUserDetailsService;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MobileAuthService {
 private final JdbcTemplate jdbc;
 private final SimertpiUserDetailsService users;
 private final UserRepository repository;
 private final DaoAuthenticationProvider provider;
 private final Duration accessTtl, refreshTtl;
 private final SecureRandom random=new SecureRandom();
 public record SessionResponse(UUID userId,String tokenType,String accessToken,String refreshToken,
     OffsetDateTime accessExpiresAt,OffsetDateTime refreshExpiresAt) {
  @Override public String toString(){return "SessionResponse[redacted]";}
 }
 private record Session(UUID id,UUID userId,OffsetDateTime expires,boolean revoked) {}
 public MobileAuthService(JdbcTemplate jdbc,SimertpiUserDetailsService users,UserRepository repository,
     PasswordEncoder encoder,@Value("${simertpi.auth.access-ttl:15m}") Duration accessTtl,
     @Value("${simertpi.auth.refresh-ttl:30d}") Duration refreshTtl) {
  if(accessTtl.isNegative()||accessTtl.isZero()||refreshTtl.compareTo(accessTtl)<=0)
   throw new IllegalArgumentException("Invalid mobile session lifetimes");
  this.jdbc=jdbc;this.users=users;this.repository=repository;
  this.accessTtl=accessTtl;this.refreshTtl=refreshTtl;
  provider=new DaoAuthenticationProvider(users);provider.setPasswordEncoder(encoder);
 }
 @Transactional
 public SessionResponse login(String username,String password) {
  Authentication authentication=provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username,password));
  citizen((UserDetails)authentication.getPrincipal());
  UUID userId=repository.findByUsername(username).orElseThrow(MobileAuthService::invalid).getId();
  OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS),expires=now.plus(refreshTtl);
  String access=token(),refresh=token();UUID id=UUID.randomUUID();
  jdbc.update("INSERT INTO identity.mobile_sessions(id,user_id,access_hash,access_expires_at,refresh_expires_at) VALUES (?,?,?,?,?)",
    id,userId,hash(access),now.plus(accessTtl),expires);
  jdbc.update("INSERT INTO identity.mobile_refresh_tokens(token_hash,session_id) VALUES (?,?)",hash(refresh),id);
  return new SessionResponse(userId,"Bearer",access,refresh,now.plus(accessTtl),expires);
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public SessionResponse refresh(String refresh) {
  Session session=locked(refresh);
  OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  Boolean used=jdbc.queryForObject("SELECT used_at IS NOT NULL FROM identity.mobile_refresh_tokens WHERE token_hash=?",Boolean.class,hash(refresh));
  if(session.revoked()||!session.expires().isAfter(now)||Boolean.TRUE.equals(used)) {
   revoke(session.id());throw invalid();
  }
  try {citizen(details(session.userId()));} catch(AuthenticationException exception) {revoke(session.id());throw invalid();}
  String access=token(),next=token();OffsetDateTime accessExpiry=now.plus(accessTtl);
  if(accessExpiry.isAfter(session.expires())) accessExpiry=session.expires();
  jdbc.update("UPDATE identity.mobile_refresh_tokens SET used_at=? WHERE token_hash=?",now,hash(refresh));
  jdbc.update("INSERT INTO identity.mobile_refresh_tokens(token_hash,session_id) VALUES (?,?)",hash(next),session.id());
  jdbc.update("UPDATE identity.mobile_sessions SET access_hash=?,access_expires_at=? WHERE id=?",hash(access),accessExpiry,session.id());
  return new SessionResponse(session.userId(),"Bearer",access,next,accessExpiry,session.expires());
 }
 @Transactional
 public void logout(String refresh) {revoke(locked(refresh).id());}
 @Transactional(readOnly=true)
 public UserDetails authenticate(String access) {
  String key=hash(access);
  List<UUID> ids=jdbc.query("SELECT user_id FROM identity.mobile_sessions WHERE access_hash=? AND revoked_at IS NULL AND access_expires_at>CURRENT_TIMESTAMP AND refresh_expires_at>CURRENT_TIMESTAMP",
    (rs,n)->rs.getObject(1,UUID.class),key);
  if(ids.isEmpty()) throw invalid();
  UserDetails details=details(ids.getFirst());citizen(details);return details;
 }
 private UserDetails details(UUID id){return users.loadUserByUsername(repository.findById(id).orElseThrow(MobileAuthService::invalid).getUsername());}
 private Session locked(String refresh) {
  List<Session> sessions=jdbc.query("SELECT s.id,s.user_id,s.refresh_expires_at,s.revoked_at IS NOT NULL AS revoked FROM identity.mobile_sessions s JOIN identity.mobile_refresh_tokens r ON r.session_id=s.id WHERE r.token_hash=? FOR UPDATE OF s",
    (rs,n)->new Session(rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),rs.getObject("refresh_expires_at",OffsetDateTime.class),rs.getBoolean("revoked")),hash(refresh));
  if(sessions.isEmpty()) throw invalid();return sessions.getFirst();
 }
 private void revoke(UUID id){jdbc.update("UPDATE identity.mobile_sessions SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE id=?",id);}
 private static void citizen(UserDetails user){
  if(!user.isEnabled()||user.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("CITIZEN"))) throw invalid();
 }
 private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 public static String hash(String token){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("Digest unavailable");}
 }
 private static BadCredentialsException invalid(){return new BadCredentialsException("Authentication required");}
}
