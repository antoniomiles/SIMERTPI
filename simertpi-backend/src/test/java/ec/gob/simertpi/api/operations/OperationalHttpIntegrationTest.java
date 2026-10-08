package ec.gob.simertpi.api.operations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.*;
import org.springframework.boot.actuate.health.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@org.springframework.context.annotation.Import(OperationalHttpIntegrationTest.TestConfig.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"simertpi.payments.provider=UNCONFIGURED"})
class OperationalHttpIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {
 @Autowired TestRestTemplate http;@LocalServerPort int port;@Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder encoder;@Autowired Flyway flyway;
 @MockitoSpyBean(name="dbHealthContributor") HealthContributor database;
 UUID user;String name;
 @BeforeEach void setup(){user=UUID.randomUUID();name="cp14-"+user;jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name) VALUES (?,?,?,?,'Ops','Test')",user,name,name+"@example.test",encoder.encode("ops-test"));jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='SIMERTPI_ADMIN'",user);}
 @AfterEach void cleanup(){jdbc.update("DELETE FROM identity.user_roles WHERE user_id=?",user);jdbc.update("DELETE FROM identity.users WHERE id=?",user);}
 ResponseEntity<String> get(String path,boolean admin){HttpHeaders headers=new HttpHeaders();if(admin)headers.setBasicAuth(name,"ops-test");headers.set("X-Correlation-ID","cp14-trace");return http.exchange("http://localhost:"+port+path,HttpMethod.GET,new HttpEntity<>(headers),String.class);}
 @ParameterizedTest @ValueSource(strings={"/actuator/health","/actuator/health/liveness","/actuator/health/readiness"}) void healthIsPublicAndSanitized(String path){var result=get(path,false);assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(result.getBody()).contains("UP").doesNotContain("jdbc","password","components","details");assertThat(result.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("cp14-trace");}
 @Test void databaseDownAffectsReadinessButNotLiveness(){doReturn(Health.down().withDetail("secret","fixture-secret").build()).when((HealthIndicator)database).health();assertThat(get("/actuator/health/readiness",false).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);assertThat(get("/actuator/health/readiness",false).getBody()).doesNotContain("fixture-secret");assertThat(get("/actuator/health/liveness",false).getStatusCode()).isEqualTo(HttpStatus.OK);}
 @ParameterizedTest @ValueSource(strings={"/actuator/metrics","/actuator/prometheus","/actuator/info"}) void operationalEndpointsRequireAdmin(String path){assertThat(get(path,false).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);var admin=get(path,true);assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(admin.getBody()).doesNotContain("simertpi_dev_2026","fixture-secret","password");}
 @ParameterizedTest @ValueSource(strings={"env","beans","configprops","heapdump","threaddump","mappings"}) void sensitiveActuatorEndpointsAreClosed(String endpoint){assertThat(get("/actuator/"+endpoint,true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);}
 @Test void flywayValidatesCurrentSchema(){flyway.validate();assertThat(flyway.info().current().getVersion().toString()).isEqualTo("35");}

 @org.springframework.boot.test.context.TestConfiguration static class TestConfig {
  @org.springframework.context.annotation.Bean TestErrorController testErrorController(){return new TestErrorController();}
 }
 @org.springframework.web.bind.annotation.RestController static class TestErrorController {
  @org.springframework.web.bind.annotation.GetMapping("/api/v1/operations/test/error") public void fail(){throw new IllegalStateException("fixture-secret SQL /private/path");}
 }
 @Test void operationalErrorPreservesCorrelationWithoutInternalDetails(){var response=get("/api/v1/operations/test/error",true);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);assertThat(response.getBody()).contains("correlationId","cp14-trace").doesNotContain("fixture-secret","SQL","/private/path","IllegalStateException");}
}
