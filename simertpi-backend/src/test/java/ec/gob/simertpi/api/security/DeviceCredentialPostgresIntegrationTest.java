package ec.gob.simertpi.api.security;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceCredentialPostgresIntegrationTest extends AbstractPostgresIntegrationTest {
 @Autowired JdbcTemplate jdbc;@Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired PasswordEncoder encoder;
 UUID user;String name,access,refresh;KeyPair key;JsonNode registration;
 static final String PASSWORD="device-fixture-only";
 @BeforeEach void setup() throws Exception {
  user=UUID.randomUUID();name="device-"+user;
  jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?, 'Device','Fixture',true)",user,name,name+"@example.invalid",encoder.encode(PASSWORD));
  jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='CITIZEN'",user);
  var login=json.readTree(http.postForEntity("/api/v1/auth/login",Map.of("username",name,"password",PASSWORD),String.class).getBody());access=login.get("accessToken").asText();refresh=login.get("refreshToken").asText();
  var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();
  var response=post("register/challenge",Map.of("deviceId",UUID.randomUUID(),"publicKey",Base64.getEncoder().encodeToString(key.getPublic().getEncoded())),access);
  assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);registration=json.readTree(response.getBody());
 }
 ResponseEntity<String> post(String route,Object body,String bearer){var headers=new HttpHeaders();if(bearer!=null)headers.setBearerAuth(bearer);return http.exchange("/api/v1/auth/device/"+route,HttpMethod.POST,new HttpEntity<>(body,headers),String.class);}
 Map<String,Object> proof(JsonNode challenge) throws Exception {var signer=Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());signer.update(challenge.get("payload").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));return Map.of("credentialId",challenge.get("credentialId").asText(),"challengeId",challenge.get("challengeId").asText(),"signature",Base64.getEncoder().encodeToString(signer.sign()));}
 void register()throws Exception{assertThat(post("register/verify",proof(registration),access).getStatusCode()).isEqualTo(HttpStatus.OK);}
 JsonNode challenge()throws Exception{var response=post("challenge",Map.of("credentialId",registration.get("credentialId").asText()),null);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return json.readTree(response.getBody());}
 @Test void logoutKeepsCredentialButSignatureCreatesNewSession()throws Exception {
  register();assertThat(http.postForEntity("/api/v1/auth/logout",Map.of("refreshToken",refresh),String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  var response=post("authenticate",proof(challenge()),null);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);var next=json.readTree(response.getBody());
  assertThat(next.get("userId").asText()).isEqualTo(user.toString());assertThat(next.get("refreshToken").asText()).isNotEqualTo(refresh);
  assertThat(http.postForEntity("/api/v1/auth/refresh",Map.of("refreshToken",refresh),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(response.getHeaders().getCacheControl()).contains("no-store");
 }
 @Test void replayAndExpiredChallengeRejected()throws Exception {
  register();var p=proof(challenge());assertThat(post("authenticate",p,null).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(post("authenticate",p,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  var expired=challenge();jdbc.update("UPDATE identity.device_challenges SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",UUID.fromString(expired.get("challengeId").asText()));
  assertThat(post("authenticate",proof(expired),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void invalidSignatureConsumedAndWrongPurposeRejected()throws Exception {
  register();var c=challenge();var valid=proof(c);var invalid=new HashMap<>(valid);invalid.put("signature",Base64.getEncoder().encodeToString(new byte[256]));
  assertThat(post("authenticate",invalid,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(post("authenticate",valid,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  assertThat(post("authenticate",proof(registration),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void revokedCredentialAndOutstandingChallengeRejected()throws Exception {
  register();var p=proof(challenge());assertThat(post("revoke",Map.of("credentialId",registration.get("credentialId").asText()),access).getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(post("authenticate",p,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(post("challenge",Map.of("credentialId",registration.get("credentialId").asText()),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void unlockValidatesExistingSessionWithoutCreatingAnother()throws Exception {
  register();Long before=jdbc.queryForObject("SELECT count(*) FROM identity.mobile_sessions WHERE user_id=?",Long.class,user);
  var c=json.readTree(post("unlock/challenge",Map.of("credentialId",registration.get("credentialId").asText()),access).getBody());
  assertThat(post("unlock/verify",proof(c),access).getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.mobile_sessions WHERE user_id=?",Long.class,user)).isEqualTo(before);
  http.postForEntity("/api/v1/auth/logout",Map.of("refreshToken",refresh),String.class);
  assertThat(post("unlock/challenge",Map.of("credentialId",registration.get("credentialId").asText()),access).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void wrongAccountCannotRegisterUnlockOrRevoke()throws Exception {
  register();UUID other=UUID.randomUUID();String otherName="other-"+other;
  jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?, 'Other','Fixture',true)",other,otherName,otherName+"@example.invalid",encoder.encode(PASSWORD));
  jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='CITIZEN'",other);
  var session=json.readTree(http.postForEntity("/api/v1/auth/login",Map.of("username",otherName,"password",PASSWORD),String.class).getBody());String token=session.get("accessToken").asText();var id=Map.of("credentialId",registration.get("credentialId").asText());
  assertThat(post("revoke",id,token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(post("unlock/challenge",id,token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void simultaneousProofOnlyCreatesOneSession()throws Exception {
  register();var p=proof(challenge());try(var pool=Executors.newFixedThreadPool(2)){var barrier=new CyclicBarrier(2);Callable<HttpStatusCode> task=()->{barrier.await();return post("authenticate",p,null).getStatusCode();};var a=pool.submit(task);var b=pool.submit(task);assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.UNAUTHORIZED);}
 }
 @Test void excessiveChallengesAndDisabledAccountRejected()throws Exception {
  register();for(int i=0;i<4;i++)challenge();assertThat(post("challenge",Map.of("credentialId",registration.get("credentialId").asText()),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  jdbc.update("UPDATE identity.users SET enabled=false WHERE id=?",user);assertThat(post("authenticate",proof(registration),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
 @Test void registrationRequiresRecentPasswordSession() {
  jdbc.update("UPDATE identity.mobile_sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '6 minutes' WHERE user_id=?",user);
  assertThat(post("register/challenge",Map.of("deviceId",UUID.randomUUID(),"publicKey",Base64.getEncoder().encodeToString(key.getPublic().getEncoded())),access).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
 }
}
