package ec.gob.simertpi.application.identity.admin;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AdminBootstrapService {
 private final UserRepository users;private final RoleRepository roles;private final PasswordEncoder encoder;private final JdbcTemplate jdbc;private final AuditService audit;
 public AdminBootstrapService(UserRepository users,RoleRepository roles,PasswordEncoder encoder,JdbcTemplate jdbc,AuditService audit){this.users=users;this.roles=roles;this.encoder=encoder;this.jdbc=jdbc;this.audit=audit;}
 public record Identity(String username,String email,String firstName,String lastName) {}
 @Transactional
 public UUID createFirstAdmin(Identity identity,CharSequence password,String authorization) {
  return createFirst(identity,password,authorization,"SIMERTPI_ADMIN");
 }
 @Transactional
 public UUID createFirstSuperadmin(Identity identity,CharSequence password,String authorization) {
  return createFirst(identity,password,authorization,"IT_ADMIN");
 }
 private UUID createFirst(Identity identity,CharSequence password,String authorization,String roleCode) {
  if(identity==null||identity.username()==null||!identity.username().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,99}")||identity.email()==null||identity.email().length()>255||!identity.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||identity.firstName()==null||identity.firstName().isBlank()||identity.firstName().length()>100||identity.lastName()==null||identity.lastName().isBlank()||identity.lastName().length()>100)throw new IllegalArgumentException("Invalid bootstrap identity");
  if(password==null||Character.codePointCount(password,0,password.length())<16||StandardCharsets.UTF_8.encode(CharBuffer.wrap(password)).remaining()>72||authorization==null||!authorization.matches("[A-Za-z0-9._:/-]{3,100}"))throw new IllegalArgumentException("Invalid bootstrap input");
  var actor=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
  if(actor==null||!actor.isAuthenticated()||actor.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("BOOTSTRAP_OPERATOR")))throw new IllegalStateException("Explicit bootstrap operator required");
  jdbc.execute("SELECT pg_advisory_xact_lock(25250342)");
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.user_roles ur JOIN identity.roles r ON r.id=ur.role_id WHERE r.code=? AND r.code IN ('SIMERTPI_ADMIN','IT_ADMIN'))",Boolean.class,roleCode)))throw new IllegalStateException("An account for this administrator role already exists");
  String normalizedUsername=identity.username().trim();String normalizedEmail=identity.email().trim().toLowerCase(Locale.ROOT);
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('username:' || lower(?), 0))",rs->null,normalizedUsername);
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(lower(?), 0))",rs->null,normalizedEmail);
  if(users.existsByUsernameIgnoreCase(normalizedUsername)||users.existsByEmailIgnoreCase(normalizedEmail))throw new IllegalStateException("Identity already exists; no promotion performed");
  var role=roles.findByCode(roleCode).orElseThrow(()->new IllegalStateException("Administrator role missing"));
  User user=new User();user.setId(UUID.randomUUID());user.setUsername(normalizedUsername);user.setEmail(normalizedEmail);user.setFirstName(identity.firstName().trim());user.setLastName(identity.lastName().trim());user.setPasswordHash(encoder.encode(password));user.setEnabled(true);user.setCreatedAt(OffsetDateTime.now());user.setUpdatedAt(user.getCreatedAt());user.getRoles().add(role);
  users.saveAndFlush(user);
  User stored=users.findByUsernameWithRoles(identity.username()).orElseThrow();
  if(!stored.isEnabled()||stored.getRoles().size()!=1||!stored.getRoles().iterator().next().getCode().equals(roleCode))throw new IllegalStateException("Bootstrap verification failed");
  audit.success("ADMIN_BOOTSTRAP","USER",stored.getId(),null,Map.of("approvalId",authorization,"role",roleCode));
  return stored.getId();
 }
}
