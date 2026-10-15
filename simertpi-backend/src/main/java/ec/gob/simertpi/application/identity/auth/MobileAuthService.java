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
 private final Duration accessTtl, refreshTtl, municipalAccessTtl, municipalRefreshTtl;
 private final SecureRandom random=new SecureRandom();
 public record SessionResponse(UUID userId,String tokenType,String accessToken,String refreshToken,
     OffsetDateTime accessExpiresAt,OffsetDateTime refreshExpiresAt) {
  @Override public String toString(){return "SessionResponse[redacted]";}
 }
 private enum Audience { CITIZEN, MUNICIPAL }
 private record Session(UUID id,UUID userId,OffsetDateTime expires,boolean revoked,String audience) {}
 private record AccessSession(UUID id,UUID userId) {}
 public MobileAuthService(JdbcTemplate jdbc,SimertpiUserDetailsService users,UserRepository repository,
     PasswordEncoder encoder,@Value("${simertpi.auth.access-ttl:15m}") Duration accessTtl,
     @Value("${simertpi.auth.refresh-ttl:30d}") Duration refreshTtl,
     @Value("${simertpi.municipal-auth.access-ttl:15m}") Duration municipalAccessTtl,
     @Value("${simertpi.municipal-auth.refresh-ttl:30d}") Duration municipalRefreshTtl) {
  if(accessTtl.isNegative()||accessTtl.isZero()||refreshTtl.compareTo(accessTtl)<=0)
   throw new IllegalArgumentException("Invalid mobile session lifetimes");
  if(municipalAccessTtl.isNegative()||municipalAccessTtl.isZero()||municipalRefreshTtl.compareTo(municipalAccessTtl)<=0)
   throw new IllegalArgumentException("Invalid municipal session lifetimes");
  this.jdbc=jdbc;this.users=users;this.repository=repository;
  this.accessTtl=accessTtl;this.refreshTtl=refreshTtl;
  this.municipalAccessTtl=municipalAccessTtl;this.municipalRefreshTtl=municipalRefreshTtl;
  provider=new DaoAuthenticationProvider(users);provider.setPasswordEncoder(encoder);
 }
 @Transactional
 public SessionResponse login(String username,String password) {
  Authentication authentication=provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username,password));
  citizen((UserDetails)authentication.getPrincipal());
  UUID userId=repository.findByUsername(username).orElseThrow(MobileAuthService::invalid).getId();
  return issue(userId,"PASSWORD",Audience.CITIZEN);
 }
 @Transactional
 public SessionResponse municipalLogin(String username,String password) {
  Authentication authentication=provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username,password));
  inspector((UserDetails)authentication.getPrincipal());
  UUID userId=repository.findByUsername(username).orElseThrow(MobileAuthService::invalid).getId();
  return issue(userId,"PASSWORD",Audience.MUNICIPAL);
 }
 @Transactional
 public SessionResponse issueDeviceSession(UUID userId) {
  citizen(details(userId));return issue(userId,"DEVICE",Audience.CITIZEN);
 }
 @Transactional(readOnly=true)
 public UUID deviceOwner(String access, boolean recentPassword) {
  authenticate(access);
  var owners=jdbc.query("SELECT user_id FROM identity.mobile_sessions WHERE access_hash=? AND revoked_at IS NULL AND access_expires_at>CURRENT_TIMESTAMP AND refresh_expires_at>CURRENT_TIMESTAMP"+
   (recentPassword?" AND authentication_method='PASSWORD' AND created_at>CURRENT_TIMESTAMP-INTERVAL '5 minutes'":""),
   (rs,n)->rs.getObject(1,UUID.class),hash(access));
  if(owners.isEmpty())throw invalid();return owners.getFirst();
 }
 private SessionResponse issue(UUID userId, String method, Audience audience) {
  OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  Duration effectiveAccessTtl=audience==Audience.MUNICIPAL?municipalAccessTtl:accessTtl;
  Duration effectiveRefreshTtl=audience==Audience.MUNICIPAL?municipalRefreshTtl:refreshTtl;
  OffsetDateTime expires=now.plus(effectiveRefreshTtl);
  String access=token(),refresh=token();UUID id=UUID.randomUUID();
  jdbc.update("INSERT INTO identity.mobile_sessions(id,user_id,access_hash,access_expires_at,refresh_expires_at,authentication_method,audience) VALUES (?,?,?,?,?,?,?)",
    id,userId,hash(access),now.plus(effectiveAccessTtl),expires,method,audience.name());
  jdbc.update("INSERT INTO identity.mobile_refresh_tokens(token_hash,session_id) VALUES (?,?)",hash(refresh),id);
  return new SessionResponse(userId,"Bearer",access,refresh,now.plus(effectiveAccessTtl),expires);
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public SessionResponse refresh(String refresh) {
  return refresh(refresh,Audience.CITIZEN);
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public SessionResponse municipalRefresh(String refresh) {
  return refresh(refresh,Audience.MUNICIPAL);
 }
 private SessionResponse refresh(String refresh,Audience audience) {
  Session session=locked(refresh);
  OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  Boolean used=jdbc.queryForObject("SELECT used_at IS NOT NULL FROM identity.mobile_refresh_tokens WHERE token_hash=?",Boolean.class,hash(refresh));
  if(!session.audience().equals(audience.name()))throw invalid();
  if(session.revoked()||!session.expires().isAfter(now)||Boolean.TRUE.equals(used)) {
   revoke(session.id());throw invalid();
  }
  try {requireSessionAudience(details(session.userId()),audience);} catch(AuthenticationException exception) {revoke(session.id());throw invalid();}
  Duration effectiveAccessTtl=audience==Audience.MUNICIPAL?municipalAccessTtl:accessTtl;
  String access=token(),next=token();OffsetDateTime accessExpiry=now.plus(effectiveAccessTtl);
  if(accessExpiry.isAfter(session.expires())) accessExpiry=session.expires();
  jdbc.update("UPDATE identity.mobile_refresh_tokens SET used_at=? WHERE token_hash=?",now,hash(refresh));
  jdbc.update("INSERT INTO identity.mobile_refresh_tokens(token_hash,session_id) VALUES (?,?)",hash(next),session.id());
  jdbc.update("UPDATE identity.mobile_sessions SET access_hash=?,access_expires_at=? WHERE id=?",hash(access),accessExpiry,session.id());
  return new SessionResponse(session.userId(),"Bearer",access,next,accessExpiry,session.expires());
 }
 @Transactional
 public void logout(String refresh) {logout(refresh,Audience.CITIZEN);}
 @Transactional
 public void municipalLogout(String refresh) {logout(refresh,Audience.MUNICIPAL);}
 private void logout(String refresh,Audience audience) {
  Session session=locked(refresh);
  if(!session.audience().equals(audience.name()))throw invalid();
  revoke(session.id());
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public UserDetails authenticate(String access) {
  return authenticate(access,Audience.CITIZEN);
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public UserDetails authenticateMunicipal(String access) {
  return authenticate(access,Audience.MUNICIPAL);
 }
 private UserDetails authenticate(String access,Audience audience) {
  String key=hash(access);
  List<AccessSession> sessions=jdbc.query("SELECT id,user_id FROM identity.mobile_sessions WHERE access_hash=? AND audience=? AND revoked_at IS NULL AND access_expires_at>CURRENT_TIMESTAMP AND refresh_expires_at>CURRENT_TIMESTAMP",
    (rs,n)->new AccessSession(rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class)),key,audience.name());
  if(sessions.isEmpty()) throw invalid();
  AccessSession session=sessions.getFirst();
  try {UserDetails details=details(session.userId());requireSessionAudience(details,audience);return details;}
  catch(AuthenticationException exception){if(audience==Audience.MUNICIPAL)revoke(session.id());throw invalid();}
 }
 @Transactional(readOnly=true)
 public boolean isMunicipalToken(String access) {
  if(access==null||!access.matches("[A-Za-z0-9_-]{43}"))return false;
  return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.mobile_sessions WHERE access_hash=? AND audience='MUNICIPAL')",Boolean.class,hash(access)));
 }
 private UserDetails details(UUID id){return users.loadUserByUsername(repository.findById(id).orElseThrow(MobileAuthService::invalid).getUsername());}
 private Session locked(String refresh) {
  List<Session> sessions=jdbc.query("SELECT s.id,s.user_id,s.refresh_expires_at,s.revoked_at IS NOT NULL AS revoked,s.audience FROM identity.mobile_sessions s JOIN identity.mobile_refresh_tokens r ON r.session_id=s.id WHERE r.token_hash=? FOR UPDATE OF s",
    (rs,n)->new Session(rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),rs.getObject("refresh_expires_at",OffsetDateTime.class),rs.getBoolean("revoked"),rs.getString("audience")),hash(refresh));
  if(sessions.isEmpty()) throw invalid();return sessions.getFirst();
 }
 private void revoke(UUID id){jdbc.update("UPDATE identity.mobile_sessions SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE id=?",id);}
 private static void citizen(UserDetails user){
  if(!user.isEnabled()||user.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("CITIZEN"))) throw invalid();
 }
 private static void inspector(UserDetails user){
  if(!user.isEnabled())throw invalid();
  if(user.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("INSPECTOR")))
   throw new org.springframework.security.access.AccessDeniedException("Municipal role required");
 }
 private static void requireAudience(UserDetails user,Audience audience){
  if(audience==Audience.CITIZEN)citizen(user);else inspector(user);
 }
 private static void requireSessionAudience(UserDetails user,Audience audience){
  if(audience==Audience.CITIZEN)citizen(user);
  else if(!user.isEnabled()||user.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("INSPECTOR")))throw invalid();
 }
 private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 public static String hash(String token){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("Digest unavailable");}
 }
 private static BadCredentialsException invalid(){return new BadCredentialsException("Authentication required");}
}
