package ec.gob.simertpi.api.security;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.application.identity.auth.MobileAuthService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MobileAuthPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
 @Autowired JdbcTemplate jdbc;@Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired PasswordEncoder encoder;
 UUID user;String name;static final String PASSWORD="mobile-fixture-only";
 @BeforeEach void setup(){user=UUID.randomUUID();name="mobile-"+user;
  jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?, 'Mobile','Fixture',true)",user,name,name+"@example.invalid",encoder.encode(PASSWORD));
  jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='CITIZEN'",user);
 }
 ResponseEntity<String> post(String path,Object request){HttpHeaders h=new HttpHeaders();h.set("X-Correlation-ID","cp18.1-test");return http.exchange("/api/v1/auth/"+path,HttpMethod.POST,new HttpEntity<>(request,h),String.class);}
 JsonNode login()throws Exception{var response=post("login",Map.of("username",name,"password",PASSWORD));assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return json.readTree(response.getBody());}
 ResponseEntity<String> read(String token,String path){HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);return http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),String.class);}
 @Test void loginIssuesOpaqueTokensAndPreservesCorrelation()throws Exception{
  var r=post("login",Map.of("username",name,"password",PASSWORD));assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(r.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("cp18.1-test");assertThat(r.getHeaders().getCacheControl()).contains("no-store");
  var body=json.readTree(r.getBody());assertThat(body.get("userId").asText()).isEqualTo(user.toString());assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
  assertThat(body.get("accessToken").asText()).matches("[A-Za-z0-9_-]{43}");assertThat(r.getBody()).doesNotContain(PASSWORD,"passwordHash");
  assertThat(jdbc.queryForObject("SELECT access_hash FROM identity.mobile_sessions WHERE user_id=?",String.class,user)).isEqualTo(MobileAuthService.hash(body.get("accessToken").asText()));
 }
 @Test void invalidUnknownDisabledAndNonCitizenCannotLogin(){
  assertThat(post("login",Map.of("username",name,"password","incorrect")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(post("login",Map.of("username","absent-fixture","password",PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  jdbc.update("UPDATE identity.users SET enabled=false WHERE id=?",user);
  assertThat(post("login",Map.of("username",name,"password",PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  jdbc.update("UPDATE identity.users SET enabled=true WHERE id=?",user);jdbc.update("DELETE FROM identity.user_roles WHERE user_id=?",user);
  assertThat(post("login",Map.of("username",name,"password",PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void bearerAccessUsesExistingRolesAndBasicRemainsCompatible()throws Exception{
  var s=login();assertThat(read(s.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(read(s.get("accessToken").asText(),"/actuator/metrics").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  assertThat(http.withBasicAuth(name,PASSWORD).getForEntity("/api/v1/notifications/preferences",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
 }
 @Test void invalidExpiredAndDisabledAccessRejected()throws Exception{
  var s=login();String access=s.get("accessToken").asText();assertThat(read("invalid","/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  jdbc.update("UPDATE identity.mobile_sessions SET access_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE user_id=?",user);
  assertThat(read(access,"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  s=login();jdbc.update("UPDATE identity.users SET enabled=false WHERE id=?",user);
  assertThat(read(s.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void refreshRotatesBothTokensAndReuseRevokesSession()throws Exception{
  var initial=login();String refresh=initial.get("refreshToken").asText();var response=post("refresh",Map.of("refreshToken",refresh));
  assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);var next=json.readTree(response.getBody());
  assertThat(next.get("refreshToken").asText()).isNotEqualTo(refresh);assertThat(next.get("refreshExpiresAt")).isEqualTo(initial.get("refreshExpiresAt"));
  assertThat(read(initial.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(read(next.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(post("refresh",Map.of("refreshToken",refresh)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(read(next.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void expiredAndUnknownRefreshDoNotIssueTokens()throws Exception{
  var s=login();jdbc.update("UPDATE identity.mobile_sessions SET access_expires_at=CURRENT_TIMESTAMP-INTERVAL '2 seconds',refresh_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE user_id=?",user);
  assertThat(post("refresh",Map.of("refreshToken",s.get("refreshToken").asText())).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(post("refresh",Map.of("refreshToken","A".repeat(43))).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void logoutIsIdempotentRevokesOnlyOwnSession()throws Exception{
  var first=login();var other=login();var body=Map.of("refreshToken",first.get("refreshToken").asText());
  assertThat(post("logout",body).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);assertThat(post("logout",body).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  assertThat(read(first.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(read(other.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.OK);
 }
 @Test void concurrentRefreshCannotRotateTwice()throws Exception{
  var initial=login();var body=Map.of("refreshToken",initial.get("refreshToken").asText());
  try(var pool=Executors.newFixedThreadPool(2)){
   var barrier=new CyclicBarrier(2);
   Callable<HttpStatusCode> task=()->{barrier.await();return post("refresh",body).getStatusCode();};
   var a=pool.submit(task);var b=pool.submit(task);
   assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.UNAUTHORIZED);
  }
 }
 @Test void refreshTokenCannotBeUsedAsAccess()throws Exception{var s=login();assertThat(read(s.get("refreshToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);}
 @Test void authRoutesDoNotOpenOtherMethods(){assertThat(http.getForEntity("/api/v1/auth/login",String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);}
 @Test void originPathsRejectRegistrationBeforePersistence() {
  String registrationName="registration-"+UUID.randomUUID();
  var request=Map.of("username",registrationName,"email",registrationName+"@example.invalid",
    "password",PASSWORD,"firstName","Registration","lastName","Fixture");
  assertThat(http.postForEntity("/users",request,String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(http.postForEntity("/auth/login",Map.of("username",registrationName,"password",PASSWORD),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.users WHERE username=?",Long.class,registrationName)).isZero();
 }
 @Test void publicRegistrationCreatesCitizenThatCanLoginRefreshAndUseBearer()throws Exception {
  String registrationName="registration-"+UUID.randomUUID();
  HttpHeaders headers=new HttpHeaders();headers.set("X-Correlation-ID","cp21-5-registration");
  var request=Map.of("username",registrationName,"email",registrationName+"@example.invalid",
    "password",PASSWORD,"firstName","Registration","lastName","Fixture");
  var registered=http.exchange("/api/v1/users",HttpMethod.POST,new HttpEntity<>(request,headers),String.class);
  assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  assertThat(registered.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("cp21-5-registration");
  var citizen=json.readTree(registered.getBody());UUID citizenId=UUID.fromString(citizen.get("id").asText());
  assertThat(citizen.get("username").asText()).isEqualTo(registrationName);
  assertThat(citizen.get("enabled").asBoolean()).isTrue();
  assertThat(registered.getBody()).doesNotContain(PASSWORD,"passwordHash","password_hash","accessToken","refreshToken");
  String encoded=jdbc.queryForObject("SELECT password_hash FROM identity.users WHERE id=?",String.class,citizenId);
  assertThat(encoded).isNotEqualTo(PASSWORD);assertThat(encoder.matches(PASSWORD,encoded)).isTrue();
  assertThat(jdbc.queryForList("SELECT r.code FROM identity.roles r JOIN identity.user_roles ur ON ur.role_id=r.id WHERE ur.user_id=?",String.class,citizenId)).containsExactly("CITIZEN");
  var loggedIn=post("login",Map.of("username",registrationName,"password",PASSWORD));
  assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);var initial=json.readTree(loggedIn.getBody());
  assertThat(initial.get("userId").asText()).isEqualTo(citizenId.toString());
  assertThat(initial.get("tokenType").asText()).isEqualTo("Bearer");
  assertThat(initial.get("accessToken").asText()).matches("[A-Za-z0-9_-]{43}");
  assertThat(initial.get("refreshToken").asText()).matches("[A-Za-z0-9_-]{43}");
  assertThat(read(initial.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.OK);
  var refreshed=post("refresh",Map.of("refreshToken",initial.get("refreshToken").asText()));
  assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);var rotated=json.readTree(refreshed.getBody());
  assertThat(rotated.get("refreshToken").asText()).isNotEqualTo(initial.get("refreshToken").asText());
  assertThat(read(rotated.get("accessToken").asText(),"/api/v1/notifications/preferences").getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(read(rotated.get("accessToken").asText(),"/actuator/metrics").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
 }
}
