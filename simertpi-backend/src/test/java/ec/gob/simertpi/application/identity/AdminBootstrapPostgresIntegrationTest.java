package ec.gob.simertpi.application.identity;
import ec.gob.simertpiadmin.bootstrap.*;
import ec.gob.simertpi.application.identity.admin.AdminBootstrapService;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import java.nio.CharBuffer;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
class AdminBootstrapPostgresIntegrationTest {
 static PostgreSQLContainer<?> pg;static ConfigurableApplicationContext context;
 static AdminBootstrapService service;static UserRepository users;static JdbcTemplate jdbc;static PasswordEncoder encoder;
 private static final String PASSWORD="isolated-bootstrap-fixture-2026";
 @BeforeAll static void setup(){
  System.setProperty("logging.level.root","OFF");
  pg=new PostgreSQLContainer<>("postgres:16.15").withDatabaseName("admin_bootstrap_test").withUsername("isolated").withPassword(UUID.randomUUID().toString());pg.start();
  Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().migrate();
  Map<String,Object> settings=new HashMap<>();settings.put("spring.datasource.url",pg.getJdbcUrl());settings.put("spring.datasource.username",pg.getUsername());settings.put("spring.datasource.password",pg.getPassword());settings.put("spring.jpa.hibernate.ddl-auto","none");settings.put("spring.jpa.show-sql",false);settings.put("spring.flyway.enabled",false);settings.put("logging.level.root","OFF");settings.put("spring.main.banner-mode","off");
  context=new SpringApplicationBuilder(AdminBootstrapConfiguration.class).web(WebApplicationType.NONE).initializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",settings))).run();
  service=context.getBean(AdminBootstrapService.class);users=context.getBean(UserRepository.class);jdbc=context.getBean(JdbcTemplate.class);encoder=context.getBean(PasswordEncoder.class);
 }
 @BeforeEach void clearFixtureAdmins(){jdbc.update("DELETE FROM identity.user_roles WHERE role_id IN (SELECT id FROM identity.roles WHERE code='SIMERTPI_ADMIN')");SecurityContextHolder.getContext().setAuthentication(operator());}
 @AfterEach void clearActor(){SecurityContextHolder.clearContext();}
 @AfterAll static void close(){if(context!=null)context.close();if(pg!=null)pg.stop();}
 static UsernamePasswordAuthenticationToken operator(){return UsernamePasswordAuthenticationToken.authenticated("BOOTSTRAP:isolated-operator",null,List.of(new SimpleGrantedAuthority("BOOTSTRAP_OPERATOR")));}
 static AdminBootstrapService.Identity identity(String username){return new AdminBootstrapService.Identity(username,username+"@example.invalid","Isolated","Admin");}
 @Test void nonHttpContextDoesNotHaveFlywayOrScheduling(){assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isNotEqualTo("servlet");assertThat(context.getBeansOfType(Flyway.class)).isEmpty();assertThat(context.containsBean("dispatcherServlet")).isFalse();assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();}
 @Test void createsOnlyFirstAdminAuditsAndNeverMutatesOtherAccounts(){
  String citizen="fixture-"+UUID.randomUUID();User existing=new User();existing.setId(UUID.randomUUID());existing.setUsername(citizen);existing.setEmail(citizen+"@example.invalid");existing.setFirstName("Existing");existing.setLastName("Citizen");existing.setEnabled(true);existing.setPasswordHash(encoder.encode("unrelated-fixture-only"));existing.setCreatedAt(OffsetDateTime.now());existing.setUpdatedAt(existing.getCreatedAt());users.saveAndFlush(existing);
  long count=users.count();String before=existing.getPasswordHash();String name="bootstrap-"+UUID.randomUUID();
  UUID id=service.createFirstAdmin(identity(name),CharBuffer.wrap(PASSWORD.toCharArray()),"CP25.3-C-LOCAL");
  User created=users.findByUsernameWithRoles(name).orElseThrow();assertThat(created.isEnabled()).isTrue();assertThat(created.getRoles().stream().map(r->r.getCode()).toList()).containsExactly("SIMERTPI_ADMIN");assertThat(encoder.matches(PASSWORD,created.getPasswordHash())).isTrue();
  assertThat(users.count()).isEqualTo(count+1);assertThat(users.findById(existing.getId()).orElseThrow().getPasswordHash().equals(before)).isTrue();
  String createdHash=created.getPasswordHash();assertThatThrownBy(()->service.createFirstAdmin(identity(name),"different-fixture-only","CP25.3-C-LOCAL")).isInstanceOf(IllegalStateException.class);
  assertThat(users.findById(id).orElseThrow().getPasswordHash().equals(createdHash)).isTrue();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_BOOTSTRAP' AND resource_id=? AND actor_name='BOOTSTRAP:isolated-operator'",Integer.class,id)).isEqualTo(1);
 }
 @Test void createsFirstSuperadministratorWithItAdminRoleAndRejectsSecondBootstrap(){
  String name="superadmin-"+UUID.randomUUID();UUID id=service.createFirstSuperadmin(identity(name),CharBuffer.wrap(PASSWORD.toCharArray()),"approved-local-fixture");
  User created=users.findByUsernameWithRoles(name).orElseThrow();assertThat(created.isEnabled()).isTrue();assertThat(created.getRoles().stream().map(r->r.getCode()).toList()).containsExactly("IT_ADMIN");
  assertThatThrownBy(()->service.createFirstSuperadmin(identity("second-super-"+UUID.randomUUID()),PASSWORD,"approved-local-fixture")).isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_BOOTSTRAP' AND resource_id=? AND metadata->>'role'='IT_ADMIN' AND result='SUCCESS'",Integer.class,id)).isEqualTo(1);
 }
 @Test void refusesPromotionAndRequiresExplicitOperator(){
  User user=new User();String name="existing-"+UUID.randomUUID();user.setId(UUID.randomUUID());user.setUsername(name);user.setEmail(name+"@example.invalid");user.setFirstName("Existing");user.setLastName("Account");user.setEnabled(true);user.setCreatedAt(OffsetDateTime.now());user.setUpdatedAt(user.getCreatedAt());users.saveAndFlush(user);
  assertThatThrownBy(()->service.createFirstAdmin(identity(name),PASSWORD,"CP25.3-C-LOCAL")).isInstanceOf(IllegalStateException.class);
  assertThat(users.findByUsernameWithRoles(name).orElseThrow().getRoles()).isEmpty();
  SecurityContextHolder.clearContext();assertThatThrownBy(()->service.createFirstAdmin(identity("new-local-admin"),PASSWORD,"CP25.3-C-LOCAL")).isInstanceOf(IllegalStateException.class);
 }
 @Test void concurrentCommandsCanCreateOnlyOneAdmin()throws Exception{
  var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
  try {for(int i=0;i<2;i++){String name="concurrent-"+UUID.randomUUID();results.add(pool.submit(()->{start.await();SecurityContextHolder.getContext().setAuthentication(operator());try{service.createFirstAdmin(identity(name),PASSWORD,"CP25.3-C-LOCAL");return true;}catch(IllegalStateException rejected){return false;}finally{SecurityContextHolder.clearContext();}}));}
   start.countDown();int successes=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))successes++;assertThat(successes).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE r.code='SIMERTPI_ADMIN'",Integer.class)).isEqualTo(1);
  }finally{pool.shutdownNow();}
 }
 @Test void bootstrapParserKeepsLegacyModeAndRequiresExplicitSuperadminMode(){
  assertThat(AdminBootstrapCli.parseMode(new String[]{"--bootstrap-dev-admin"})).isEqualTo(AdminBootstrapCli.BootstrapMode.DEV_ADMIN);
  assertThat(AdminBootstrapCli.parseMode(new String[]{"--bootstrap-dev-superadmin"})).isEqualTo(AdminBootstrapCli.BootstrapMode.DEV_SUPERADMIN);
  assertThat(AdminBootstrapCli.run(new String[]{"--bootstrap-dev-admin","--bootstrap-dev-superadmin"})).isEqualTo(1);
  assertThatThrownBy(()->AdminBootstrapCli.parseMode(new String[]{"--bootstrap-prod-superadmin"})).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void exactDevHostDatabasePortAndVerifiedTlsAreRequired(){
  String host=AdminBootstrapCli.RESOURCE+".internal",good="jdbc:postgresql://"+host+":5432/simertpi?sslmode=verify-full";
  assertThatCode(()->AdminBootstrapCli.verifyJdbcTarget(good,host)).doesNotThrowAnyException();
  for(String bad:List.of(good.replace(host,host+".evil.invalid"),good.replace("/simertpi?","/other?"),good.replace(":5432/",":5544/"),good.replace("verify-full","require"),good+"&password=not-allowed"))assertThatThrownBy(()->AdminBootstrapCli.verifyJdbcTarget(bad,host)).isInstanceOf(IllegalArgumentException.class);
 }
}
