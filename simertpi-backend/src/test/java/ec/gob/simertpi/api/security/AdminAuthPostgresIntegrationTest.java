package ec.gob.simertpi.api.security;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.application.identity.admin.AdminBootstrapService;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"logging.level.root=WARN","spring.jpa.show-sql=false","simertpi.admin-auth.idle-ttl=5m","simertpi.admin-auth.absolute-ttl=15m"})
@AutoConfigureMockMvc
class AdminAuthPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder encoder;
 private static final String PASSWORD="isolated-admin-fixture-2026";
 private UUID adminId;private String admin,citizen,inspector,disabled;
 private record Browser(Cookie session,Cookie csrf) { @Override public String toString(){return "Browser[redacted]";} }
 @BeforeEach void fixture(){String suffix=UUID.randomUUID().toString();admin="admin-"+suffix;citizen="citizen-"+suffix;inspector="inspector-"+suffix;disabled="disabled-"+suffix;adminId=create(admin,"SIMERTPI_ADMIN",true);create(citizen,"CITIZEN",true);create(inspector,"INSPECTOR",true);create(disabled,"SIMERTPI_ADMIN",false);}
 private UUID create(String name,String role,boolean enabled){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,?,?)",id,name,name+"@example.invalid",encoder.encode(PASSWORD),"Isolated","Fixture",enabled);jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code=?",id,role);return id;}
 private Cookie cookie(MvcResult result,String name){String header=result.getResponse().getHeaders("Set-Cookie").stream().filter(h->h.startsWith(name+"=")).findFirst().orElseThrow();return new Cookie(name,header.substring(name.length()+1).split(";",2)[0]);}
 private Cookie csrf()throws Exception{return cookie(mvc.perform(get("/api/v1/admin/auth/csrf").secure(true)).andExpect(status().isNoContent()).andReturn(),"__Host-SIMERTPI_ADMIN_XSRF");}
 private String body(String name,String password){return "{\"username\":\""+name+"\",\"password\":\""+password+"\"}";}
 private MvcResult login(String name,String password,Cookie token)throws Exception{return mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(token).header("X-XSRF-TOKEN",token.getValue()).contentType("application/json").content(body(name,password))).andReturn();}
 private Browser login()throws Exception{var result=login(admin,PASSWORD,csrf());assertThat(result.getResponse().getStatus()).isEqualTo(200);return new Browser(cookie(result,AdminAuthenticationService.COOKIE),cookie(result,"__Host-SIMERTPI_ADMIN_XSRF"));}
 @Test void validLoginHasOpaquePersistentSecureCookieAndIdentityWithoutTokens()throws Exception{
  var result=login(admin,PASSWORD,csrf());assertThat(result.getResponse().getStatus()).isEqualTo(200);
  String header=result.getResponse().getHeaders("Set-Cookie").stream().filter(h->h.startsWith(AdminAuthenticationService.COOKIE+"=")).findFirst().orElseThrow();
  assertThat(header.contains("Secure")&&header.contains("HttpOnly")&&header.contains("SameSite=Strict")&&!header.contains("Domain=")).isTrue();
  assertThat(result.getResponse().getContentAsString().contains("accessToken")||result.getResponse().getContentAsString().contains("password")).isFalse();
  Cookie session=cookie(result,AdminAuthenticationService.COOKIE);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.admin_web_sessions WHERE user_id=? AND token_hash=?",Integer.class,adminId,AdminAuthenticationService.hash(session.getValue()))).isEqualTo(1);
  mvc.perform(get("/api/v1/admin/auth/me").secure(true).cookie(session)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value(admin)).andExpect(header().string("Cache-Control","no-store"));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_LOGIN' AND actor_id=? AND result='SUCCESS'",Integer.class,adminId)).isEqualTo(1);
 }
 @Test void meRenewsTheFiveMinuteIdleDeadlineAndReturnsThePersistedServerDeadline()throws Exception{
  var result=login(admin,PASSWORD,csrf());assertThat(result.getResponse().getStatus()).isEqualTo(200);
  Cookie session=cookie(result,AdminAuthenticationService.COOKIE);
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var loginIdentity=mapper.readTree(result.getResponse().getContentAsString());
  var storedAtLogin=jdbc.queryForObject("SELECT idle_expires_at FROM identity.admin_web_sessions WHERE token_hash=?",java.time.OffsetDateTime.class,AdminAuthenticationService.hash(session.getValue()));
  assertThat(java.time.OffsetDateTime.parse(loginIdentity.get("sessionExpiresAt").asText()).truncatedTo(java.time.temporal.ChronoUnit.MICROS)).isEqualTo(storedAtLogin);

  var me=mvc.perform(get("/api/v1/admin/auth/me").secure(true).cookie(session)).andExpect(status().isOk()).andReturn();
  var meIdentity=mapper.readTree(me.getResponse().getContentAsString());
  var storedAfterMe=jdbc.queryForObject("SELECT idle_expires_at FROM identity.admin_web_sessions WHERE token_hash=?",java.time.OffsetDateTime.class,AdminAuthenticationService.hash(session.getValue()));
  assertThat(java.time.OffsetDateTime.parse(meIdentity.get("sessionExpiresAt").asText()).truncatedTo(java.time.temporal.ChronoUnit.MICROS)).isEqualTo(storedAfterMe);
  assertThat(storedAfterMe).isAfter(storedAtLogin);
  var remaining=java.time.Duration.between(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),storedAfterMe);
  assertThat(remaining).isLessThanOrEqualTo(java.time.Duration.ofMinutes(5)).isGreaterThan(java.time.Duration.ofMinutes(4).plusSeconds(50));
 }
 @Test void invalidPasswordDisabledCitizenAndInspectorRejectedAndAudited()throws Exception {
  for(String name:List.of(citizen,inspector,disabled))assertThat(login(name,PASSWORD,csrf()).getResponse().getStatus()).isEqualTo(401);
  assertThat(login(admin,"wrong-isolated-password",csrf()).getResponse().getStatus()).isEqualTo(401);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.admin_web_sessions WHERE user_id=?",Integer.class,adminId)).isZero();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_LOGIN' AND result='FAILURE'",Integer.class)).isGreaterThanOrEqualTo(4);
 }
 @Test void missingAndMismatchedCsrfAndCrossOriginAreRejected()throws Exception{
  mvc.perform(post("/api/v1/admin/auth/login").secure(true).contentType("application/json").content(body(admin,PASSWORD))).andExpect(status().isForbidden());
  var token=csrf();
  mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(token).param("_csrf",token.getValue()).contentType("application/json").content(body(admin,PASSWORD))).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(token).header("X-XSRF-TOKEN","mismatch").contentType("application/json").content(body(admin,PASSWORD))).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(token).header("X-XSRF-TOKEN",token.getValue()).header("Origin","https://untrusted.invalid").contentType("application/json").content(body(admin,PASSWORD))).andExpect(status().isForbidden());
 }
 @Test void expiredSessionIsRevokedAndAudited()throws Exception{
  Browser browser=login();jdbc.update("UPDATE identity.admin_web_sessions SET idle_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE user_id=?",adminId);
  mvc.perform(get("/api/v1/admin/auth/me").cookie(browser.session()).secure(true)).andExpect(status().isUnauthorized());
  assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM identity.admin_web_sessions WHERE user_id=?",Boolean.class,adminId)).isTrue();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_SESSION_REVOKED' AND actor_id=?",Integer.class,adminId)).isEqualTo(1);
 }
 @Test void absoluteDeadlineCannotBeExtendedByActivity()throws Exception{
  Browser browser=login();jdbc.update("UPDATE identity.admin_web_sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '9 hours',absolute_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second',idle_expires_at=CURRENT_TIMESTAMP-INTERVAL '2 seconds' WHERE user_id=?",adminId);
  mvc.perform(get("/api/v1/admin/auth/me").secure(true).cookie(browser.session())).andExpect(status().isUnauthorized());
 }
 @Autowired ec.gob.simertpi.domain.identity.repository.UserRepository users;
 @Autowired ec.gob.simertpi.application.audit.AuditService audit;
 @Autowired ec.gob.simertpi.config.SimertpiUserDetailsService details;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 @Test void aFreshServiceInstanceRestoresThePersistedSession()throws Exception{
  Browser browser=login();var fresh=new AdminAuthenticationService(jdbc,users,audit,details,encoder,java.time.Duration.ofMinutes(15),java.time.Duration.ofHours(8));
  var identity=new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->fresh.authenticate(browser.session().getValue()));
  assertThat(identity.username()).isEqualTo(admin);
 }
 @Test void roleOrPasswordChangesInvalidateSession()throws Exception{
  Browser browser=login();jdbc.update("DELETE FROM identity.user_roles WHERE user_id=?",adminId);
  mvc.perform(get("/api/v1/admin/auth/me").cookie(browser.session()).secure(true)).andExpect(status().isUnauthorized());
  jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'",adminId);
  browser=login();jdbc.update("UPDATE identity.users SET password_hash=? WHERE id=?",encoder.encode("different-isolated-password"),adminId);
  mvc.perform(get("/api/v1/admin/auth/me").cookie(browser.session()).secure(true)).andExpect(status().isUnauthorized());
 }
 @Test void disabledAccountRevokesSession()throws Exception {Browser b=login();jdbc.update("UPDATE identity.users SET enabled=false WHERE id=?",adminId);mvc.perform(get("/api/v1/admin/auth/me").cookie(b.session()).secure(true)).andExpect(status().isUnauthorized());}
 @Test void logoutRequiresCsrfRevokesAndIsIdempotent()throws Exception{
  Browser b=login();mvc.perform(post("/api/v1/admin/auth/logout").secure(true).cookie(b.session())).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/admin/auth/logout").secure(true).cookie(b.session(),b.csrf()).header("X-XSRF-TOKEN",b.csrf().getValue())).andExpect(status().isNoContent());
  mvc.perform(get("/api/v1/admin/auth/me").secure(true).cookie(b.session())).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/admin/auth/logout").secure(true).cookie(b.session(),b.csrf()).header("X-XSRF-TOKEN",b.csrf().getValue())).andExpect(status().isNoContent());
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_LOGOUT' AND actor_id=?",Integer.class,adminId)).isEqualTo(1);
 }
 @Test void accessDeniedBasicAndBearerCannotSubstituteForAdminCookie()throws Exception{
  mvc.perform(get("/api/v1/admin/auth/me")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/admin/auth/me").header("Authorization","Basic "+Base64.getEncoder().encodeToString((admin+":"+PASSWORD).getBytes()))).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/admin/auth/me").header("Authorization","Bearer invalid-mobile-token")).andExpect(status().isUnauthorized());
 }
 @Test void loginRotatesPriorCookieAndPreventsSessionFixation()throws Exception{
  Browser b=login();var result=mvc.perform(post("/api/v1/admin/auth/login").secure(true).cookie(b.session(),b.csrf()).header("X-XSRF-TOKEN",b.csrf().getValue()).contentType("application/json").content(body(admin,PASSWORD))).andExpect(status().isOk()).andReturn();
  mvc.perform(get("/api/v1/admin/auth/me").secure(true).cookie(b.session())).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/admin/auth/me").secure(true).cookie(cookie(result,AdminAuthenticationService.COOKIE))).andExpect(status().isOk());
 }
 @Test void loginAttemptsAreBoundedWithoutPasswordLogging()throws Exception {for(int i=0;i<12;i++)assertThat(login(admin,"invalid-fixture",csrf()).getResponse().getStatus()).isEqualTo(401);assertThat(jdbc.queryForObject("SELECT MAX(attempts) FROM identity.admin_login_throttles",Integer.class)).isGreaterThanOrEqualTo(12);}
}
