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
 static AdminBootstrapService service;static UserRepository users;static JdbcTemplate jdbc;static PasswordEncoder encoder;static ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService smtp;static ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService microsoft;
 private static final String PASSWORD="isolated-bootstrap-fixture-2026";
 @BeforeAll static void setup(){
  System.setProperty("logging.level.root","OFF");
  pg=new PostgreSQLContainer<>("postgres:16.15").withDatabaseName("admin_bootstrap_test").withUsername("isolated").withPassword(UUID.randomUUID().toString());pg.start();
  Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().migrate();
  Map<String,Object> settings=new HashMap<>();settings.put("spring.datasource.url",pg.getJdbcUrl());settings.put("spring.datasource.username",pg.getUsername());settings.put("spring.datasource.password",pg.getPassword());settings.put("spring.jpa.hibernate.ddl-auto","none");settings.put("spring.jpa.show-sql",false);settings.put("spring.flyway.enabled",false);settings.put("logging.level.root","OFF");settings.put("spring.main.banner-mode","off");
  context=new SpringApplicationBuilder(AdminBootstrapConfiguration.class,TestSmtpOverride.class).web(WebApplicationType.NONE).initializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",settings))).run();
  service=context.getBean(AdminBootstrapService.class);users=context.getBean(UserRepository.class);jdbc=context.getBean(JdbcTemplate.class);encoder=context.getBean(PasswordEncoder.class);smtp=context.getBean(ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.class);microsoft=context.getBean(ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService.class);
 }
 @BeforeEach void clearFixtureAdmins(){jdbc.update("DELETE FROM identity.user_roles WHERE role_id IN (SELECT id FROM identity.roles WHERE code IN ('SIMERTPI_ADMIN','IT_ADMIN'))");org.mockito.Mockito.reset(smtp,microsoft);org.mockito.Mockito.when(microsoft.isActive()).thenReturn(false);org.mockito.Mockito.when(smtp.bootstrap(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.BootstrapResult(1,"ACTIVE","GMAIL","fixture@example.invalid","smtp.gmail.com",587,"STARTTLS",OffsetDateTime.now()));SecurityContextHolder.getContext().setAuthentication(operator());}
 @AfterEach void clearActor(){SecurityContextHolder.clearContext();}
 @AfterAll static void close(){if(context!=null)context.close();if(pg!=null)pg.stop();}
 static UsernamePasswordAuthenticationToken operator(){return UsernamePasswordAuthenticationToken.authenticated("BOOTSTRAP:isolated-operator",null,List.of(new SimpleGrantedAuthority("BOOTSTRAP_OPERATOR")));}
 static AdminBootstrapService.Identity identity(String username){return new AdminBootstrapService.Identity(username,username+"@example.invalid","Isolated","Admin");}
 @Test void nonHttpContextDoesNotHaveFlywayOrScheduling(){assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isNotEqualTo("servlet");assertThat(context.getBeansOfType(Flyway.class)).isEmpty();assertThat(context.containsBean("dispatcherServlet")).isFalse();assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();}
 @Test void firstSuperadminCliRequiresExactApprovedDevIdentityAndExplicitTypedConfirmation(){
  assertThat(AdminBootstrapCli.isApprovedDevSuperadmin("superadmin.simertpi.dev","ANTOXAVIE1992@GMAIL.COM","antoxavie1992@gmail.com","CONFIRM DEV SUPERADMIN")).isTrue();
  assertThat(AdminBootstrapCli.isApprovedDevSuperadmin("superadmin.simertpi.dev","operator@example.invalid","operator@example.invalid","CONFIRM DEV SUPERADMIN")).isFalse();
  assertThat(AdminBootstrapCli.isApprovedDevSuperadmin("another-user","operator@example.invalid","operator@example.invalid","CONFIRM DEV SUPERADMIN")).isFalse();
  assertThat(AdminBootstrapCli.isApprovedDevSuperadmin("superadmin.simertpi.dev","another@example.invalid","operator@example.invalid","CONFIRM DEV SUPERADMIN")).isFalse();
  assertThat(AdminBootstrapCli.isApprovedDevSuperadmin("superadmin.simertpi.dev","operator@example.invalid","not-an-email","CONFIRM DEV SUPERADMIN")).isFalse();
  assertThat(AdminBootstrapCli.isApprovedDevSuperadmin("superadmin.simertpi.dev","operator@example.invalid","operator@example.invalid","yes")).isFalse();
 }
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
 @Test void createsFirstSuperadministratorPendingActivationWithoutPasswordAndRejectsDuplicateBootstrap(){
  String name="superadmin-"+UUID.randomUUID();var candidate=new ec.gob.simertpi.application.identity.smtp.SmtpCandidate("GMAIL","Fixture SMTP","fixture@example.invalid","smtp.gmail.com",587,"STARTTLS","fixture@example.invalid","fixture-password".toCharArray());var createdBootstrap=service.createFirstSuperadminInvite(identity(name),"approved-local-fixture",candidate);var pending=createdBootstrap.invitation();UUID id=pending.id();
  assertThat(org.mockito.Mockito.mockingDetails(smtp).getInvocations()).anyMatch(call->call.getMethod().getName().equals("bootstrap"));
  User created=users.findByUsernameWithRoles(name).orElseThrow();assertThat(created.isEnabled()).isFalse();assertThat(created.getPasswordHash()).isNull();assertThat(created.getRoles().stream().map(r->r.getCode()).toList()).containsExactly("IT_ADMIN");
  assertThat(pending.activationToken()).matches("[A-Za-z0-9_-]{43}");
  assertThat(jdbc.queryForObject("SELECT expires_at > CURRENT_TIMESTAMP AND expires_at <= CURRENT_TIMESTAMP + INTERVAL '24 hours' + INTERVAL '2 seconds' FROM identity.inspector_activation_tokens WHERE user_id=?",Boolean.class,id)).isTrue();
  assertThat(jdbc.queryForObject("SELECT token_hash <> ? FROM identity.inspector_activation_tokens WHERE user_id=?",Boolean.class,pending.activationToken(),id)).isTrue();
  context.getBean(ec.gob.simertpi.application.identity.InspectorAccountService.class).activate(pending.activationToken(),"Fixture-activation-password-2026");
  User activated=users.findByUsernameWithRoles(name).orElseThrow();assertThat(activated.isEnabled()).isTrue();assertThat(encoder.matches("Fixture-activation-password-2026",activated.getPasswordHash())).isTrue();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.inspector_activation_tokens WHERE user_id=?",Integer.class,id)).isZero();
  assertThatThrownBy(()->service.createFirstSuperadminInvite(identity("second-super-"+UUID.randomUUID()),"approved-local-fixture",candidate)).isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_BOOTSTRAP_PENDING' AND resource_id=? AND metadata->>'role'='IT_ADMIN' AND result='SUCCESS'",Integer.class,id)).isEqualTo(1);
 }
 @Test void microsoftSuperadminBootstrapRequiresActiveOAuthAndDoesNotCreateSmtpPasswordConfig(){
  String name="superadmin-oauth-"+UUID.randomUUID();
  org.mockito.Mockito.when(microsoft.isActive()).thenReturn(true);
  var invite=service.createFirstSuperadminInvite(identity(name),"approved-outlook-bootstrap");
  User created=users.findByUsernameWithRoles(name).orElseThrow();
  assertThat(created.isEnabled()).isFalse();assertThat(created.getPasswordHash()).isNull();
  assertThat(created.getRoles().stream().map(r->r.getCode()).toList()).containsExactly("IT_ADMIN");
  assertThat(invite.activationToken()).matches("[A-Za-z0-9_-]{43}");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.admin_smtp_configuration",Integer.class)).isZero();
  assertThat(jdbc.queryForObject("SELECT token_hash<>? FROM identity.inspector_activation_tokens WHERE user_id=?",Boolean.class,invite.activationToken(),invite.id())).isTrue();
  assertThatThrownBy(()->service.createFirstSuperadminInvite(identity("second-superadmin-"+UUID.randomUUID()),"approved-outlook-bootstrap"))
          .isInstanceOf(IllegalStateException.class);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE r.code='IT_ADMIN'",Integer.class)).isEqualTo(1);
 }
 @Test void microsoftBootstrapRejectsExistingCitizenEmailWithoutPromotingOrChangingIt(){
  String citizenName="citizen-oauth-collision-"+UUID.randomUUID();String citizenEmail="citizen-oauth-"+UUID.randomUUID()+"@example.invalid";
  User citizen=new User();citizen.setId(UUID.randomUUID());citizen.setUsername(citizenName);citizen.setEmail(citizenEmail);citizen.setFirstName("Existing");citizen.setLastName("Citizen");citizen.setEnabled(true);citizen.setPasswordHash(encoder.encode("unchanged-citizen-password"));citizen.setCreatedAt(OffsetDateTime.now());citizen.setUpdatedAt(citizen.getCreatedAt());users.saveAndFlush(citizen);
  jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='CITIZEN'",citizen.getId());
  String passwordHash=citizen.getPasswordHash();org.mockito.Mockito.when(microsoft.isActive()).thenReturn(true);
  assertThatThrownBy(()->service.createFirstSuperadminInvite(new AdminBootstrapService.Identity("superadmin.simertpi.dev",citizenEmail,"Bootstrap","Collision"),"approved-outlook-bootstrap"))
          .isInstanceOf(IllegalStateException.class);
  User unchanged=users.findById(citizen.getId()).orElseThrow();assertThat(unchanged.isEnabled()).isTrue();assertThat(unchanged.getEmail()).isEqualTo(citizenEmail);assertThat(unchanged.getPasswordHash()).isEqualTo(passwordHash);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='CITIZEN'",Integer.class,citizen.getId())).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='IT_ADMIN'",Integer.class,citizen.getId())).isZero();
  assertThat(users.existsByUsernameIgnoreCase("superadmin.simertpi.dev")).isFalse();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.inspector_activation_tokens WHERE user_id=?",Integer.class,citizen.getId())).isZero();
  org.mockito.Mockito.verify(smtp,org.mockito.Mockito.never()).bootstrap(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
 }
 @Test void microsoftBootstrapRequiresActiveAuthorization(){
  String name="superadmin-no-oauth-"+UUID.randomUUID();
  assertThatThrownBy(()->service.createFirstSuperadminInvite(identity(name),"approved-outlook-bootstrap")).isInstanceOf(IllegalStateException.class);
  assertThat(users.existsByUsernameIgnoreCase(name)).isFalse();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.inspector_activation_tokens t JOIN identity.users u ON u.id=t.user_id WHERE u.username=?",Integer.class,name)).isZero();
 }
 @Test void failedSmtpVerificationCreatesNoSuperadministratorOrActivationToken(){
  String name="superadmin-smtp-failed-"+UUID.randomUUID();var candidate=new ec.gob.simertpi.application.identity.smtp.SmtpCandidate("GMAIL","Fixture SMTP","fixture@example.invalid","smtp.gmail.com",587,"STARTTLS","fixture@example.invalid","fixture-password".toCharArray());
  org.mockito.Mockito.when(smtp.bootstrap(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("fixture connection rejected"));
  assertThatThrownBy(()->service.createFirstSuperadminInvite(identity(name),"approved-local-fixture",candidate)).isInstanceOf(IllegalStateException.class);
  assertThat(users.existsByUsernameIgnoreCase(name)).isFalse();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.inspector_activation_tokens t JOIN identity.users u ON u.id=t.user_id WHERE u.username=?",Integer.class,name)).isZero();
 }
 @Test void bootstrapEmailCollisionWithCitizenRejectsWithoutChangingCitizenOrCreatingInvite(){
  String name="citizen-email-collision-"+UUID.randomUUID();String email="citizen-"+UUID.randomUUID()+"@example.invalid";
  User citizen=new User();citizen.setId(UUID.randomUUID());citizen.setUsername(name);citizen.setEmail(email);citizen.setFirstName("Existing");citizen.setLastName("Citizen");citizen.setEnabled(true);citizen.setPasswordHash(encoder.encode("unchanged-citizen-fixture"));citizen.setCreatedAt(OffsetDateTime.now());citizen.setUpdatedAt(citizen.getCreatedAt());users.saveAndFlush(citizen);
  jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='CITIZEN'",citizen.getId());
  String passwordHash=citizen.getPasswordHash();var candidate=new ec.gob.simertpi.application.identity.smtp.SmtpCandidate("GMAIL","Fixture SMTP","fixture@example.invalid","smtp.gmail.com",587,"STARTTLS","fixture@example.invalid","fixture-password".toCharArray());
  assertThatThrownBy(()->service.createFirstSuperadminInvite(new AdminBootstrapService.Identity("superadmin.simertpi.dev",email,"Bootstrap","Collision"),"approved-local-fixture",candidate)).isInstanceOf(IllegalStateException.class);
  User unchanged=users.findById(citizen.getId()).orElseThrow();assertThat(unchanged.isEnabled()).isTrue();assertThat(unchanged.getEmail()).isEqualTo(email);assertThat(unchanged.getPasswordHash()).isEqualTo(passwordHash);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='CITIZEN'",Integer.class,citizen.getId())).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.code='IT_ADMIN'",Integer.class,citizen.getId())).isZero();
  assertThat(users.existsByUsernameIgnoreCase("superadmin.simertpi.dev")).isFalse();
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity.inspector_activation_tokens WHERE user_id=?",Integer.class,citizen.getId())).isZero();
  org.mockito.Mockito.verify(smtp,org.mockito.Mockito.never()).bootstrap(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
 }
 @Test void resendRotatesOnlyTheBootstrapPendingSuperadminToken(){
  String name="superadmin-resend-"+UUID.randomUUID();var candidate=new ec.gob.simertpi.application.identity.smtp.SmtpCandidate("GMAIL","Fixture SMTP","fixture@example.invalid","smtp.gmail.com",587,"STARTTLS","fixture@example.invalid","fixture-password".toCharArray());
  var original=service.createFirstSuperadminInvite(identity(name),"approved-local-fixture",candidate).invitation();
  jdbc.update("UPDATE identity.inspector_activation_tokens SET created_at=CURRENT_TIMESTAMP-INTERVAL '61 seconds' WHERE user_id=?",original.id());org.mockito.Mockito.when(smtp.configured()).thenReturn(true);
  var renewed=service.reissueFirstSuperadminInvite(identity(name),"approved-resend-fixture");
  assertThat(renewed.activationToken()).isNotEqualTo(original.activationToken());assertThat(users.findByUsernameWithRoles(name).orElseThrow().getPasswordHash()).isNull();
  var invitations=context.getBean(ec.gob.simertpi.application.identity.InspectorAccountService.class);
  assertThat(invitations.validateActivation(original.activationToken(),null).status()).isEqualTo("INVALID");assertThat(invitations.validateActivation(renewed.activationToken(),null).status()).isEqualTo("VALID");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit.functional_audit_log WHERE action='ADMIN_BOOTSTRAP_INVITATION_REISSUED' AND resource_id=? AND result='SUCCESS'",Integer.class,original.id())).isEqualTo(1);
 }
 @Test void resendRejectsPendingItAdminNotCreatedByBootstrap(){
  String name="untrusted-pending-"+UUID.randomUUID();var created=context.getBean(ec.gob.simertpi.application.identity.InspectorAccountService.class).createWithRoles(name,name+"@example.invalid","Untrusted","Pending",null,List.of("IT_ADMIN"));org.mockito.Mockito.when(smtp.configured()).thenReturn(true);
  assertThatThrownBy(()->service.reissueFirstSuperadminInvite(identity(name),"approved-resend-fixture")).isInstanceOf(IllegalStateException.class);
  assertThat(context.getBean(ec.gob.simertpi.application.identity.InspectorAccountService.class).validateActivation(created.activationToken(),null).status()).isEqualTo("VALID");
 }
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
 static class TestSmtpOverride {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService smtpConfigurationService(){return org.mockito.Mockito.mock(ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService.class);}
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService microsoftOAuthSmtpService(){return org.mockito.Mockito.mock(ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService.class);}
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
  assertThat(AdminBootstrapCli.parseMode(new String[]{"--resend-dev-superadmin-invitation"})).isEqualTo(AdminBootstrapCli.BootstrapMode.RESEND_SUPERADMIN);
  assertThat(AdminBootstrapCli.parseMode(new String[]{"--authorize-dev-microsoft-smtp"})).isEqualTo(AdminBootstrapCli.BootstrapMode.AUTHORIZE_OUTLOOK);
  assertThat(AdminBootstrapCli.parseMode(new String[]{"--activate-dev-microsoft-smtp"})).isEqualTo(AdminBootstrapCli.BootstrapMode.ACTIVATE_OUTLOOK);
  assertThat(AdminBootstrapCli.run(new String[]{"--bootstrap-dev-admin","--bootstrap-dev-superadmin"})).isEqualTo(1);
  assertThatThrownBy(()->AdminBootstrapCli.parseMode(new String[]{"--bootstrap-prod-superadmin"})).isInstanceOf(IllegalArgumentException.class);
  assertThatCode(()->AdminBootstrapCli.verifyPortalUrl("https://simertpi-admin-web-dev.onrender.com")).doesNotThrowAnyException();
  for(String bad:List.of("http://simertpi-admin-web-dev.onrender.com","https://user@example.invalid","https://example.invalid?token=x","https://example.invalid#token=x"))assertThatThrownBy(()->AdminBootstrapCli.verifyPortalUrl(bad)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void exactDevHostDatabasePortAndVerifiedTlsAreRequired(){
  String host=AdminBootstrapCli.RESOURCE+".internal",good="jdbc:postgresql://"+host+":5432/simertpi?sslmode=verify-full";
  assertThatCode(()->AdminBootstrapCli.verifyJdbcTarget(good,host)).doesNotThrowAnyException();
  for(String bad:List.of(good.replace(host,host+".evil.invalid"),good.replace("/simertpi?","/other?"),good.replace(":5432/",":5544/"),good.replace("verify-full","require"),good+"&password=not-allowed"))assertThatThrownBy(()->AdminBootstrapCli.verifyJdbcTarget(bad,host)).isInstanceOf(IllegalArgumentException.class);
 }
}
