package ec.gob.simertpi.api.security;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
/** Test-only real browser server. Always uses the disposable container infrastructure. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"server.port=8089","server.address=127.0.0.1","logging.level.root=WARN","spring.jpa.show-sql=false","simertpi.admin-auth.allowed-origin=https://localhost:4443","simertpi.admin-auth.idle-ttl=20s","simertpi.admin-auth.absolute-ttl=2m"})
@EnabledIfSystemProperty(named="simertpi.admin-browser-harness",matches="true")
class AdminBrowserHarness extends AbstractPostgresIntegrationTest {
 @Autowired UserRepository users;@Autowired RoleRepository roles;@Autowired PasswordEncoder encoder;
 @Test void serveDisposableFixturesForChrome()throws Exception {
  for(String role:List.of("SIMERTPI_ADMIN","CITIZEN","INSPECTOR","DISABLED")){
   User u=new User();String name="qa."+(role.equals("SIMERTPI_ADMIN")?"admin":role.toLowerCase());
   u.setId(UUID.randomUUID());u.setUsername(name);u.setEmail(name+"@example.invalid");u.setFirstName("Browser");u.setLastName("Fixture");u.setEnabled(!role.equals("DISABLED"));u.setCreatedAt(OffsetDateTime.now());u.setUpdatedAt(u.getCreatedAt());u.setPasswordHash(encoder.encode("chrome-isolated-fixture-2026"));u.getRoles().add(roles.findByCode(role.equals("DISABLED")?"SIMERTPI_ADMIN":role).orElseThrow());users.saveAndFlush(u);
  }
  Path stop=Path.of("target/admin-browser.stop");long deadline=System.nanoTime()+Duration.ofMinutes(12).toNanos();
  while(!Files.exists(stop)&&System.nanoTime()<deadline)Thread.sleep(100);
 }
}
