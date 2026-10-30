package ec.gob.simertpiadmin.bootstrap;
import ec.gob.simertpi.application.identity.admin.AdminBootstrapService;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.application.identity.invitation.ActivationInvitationDeliveryService;
import ec.gob.simertpi.application.identity.smtp.SmtpCandidate;
import ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService;
import java.io.Console;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AdminBootstrapCli {
 private static final String DEV_SUPERADMIN_USERNAME = "superadmin.simertpi.dev";
 private static final String DEV_SUPERADMIN_EMAIL = "antoxavi1992@outlook.com";
 private static final String DEV_SUPERADMIN_CONFIRMATION = "CONFIRM DEV SUPERADMIN";
 public static final String RESOURCE="dpg-db1hq55g1s2s73a8e340-a";
 private AdminBootstrapCli(){}
 public static void main(String[] args){System.exit(run(args));}
 public static int run(String[] args){
  char[] first=null,second=null;
  try {
   BootstrapMode mode=parseMode(args);
   Map<String,String> flags=new HashMap<>();
   if(args.length!=5)throw new IllegalArgumentException();
   for(int i=1;i<args.length;i++){String[] flag=args[i].split("=",2);if(flag.length!=2||flags.put(flag[0],flag[1])!=null)throw new IllegalArgumentException();}
   if(!flags.keySet().equals(Set.of("--expected-host","--expected-db-user","--operator","--approval-id")))throw new IllegalArgumentException();
   String host=flags.get("--expected-host"),user=flags.get("--expected-db-user"),operator=flags.get("--operator"),authorization=flags.get("--approval-id");
   if(!user.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||!operator.matches("[A-Za-z0-9._:@/-]{3,100}")||!authorization.matches("[A-Za-z0-9._:/-]{3,100}"))throw new IllegalArgumentException();
   Console console=System.console();if(console==null)throw new IllegalStateException();
   String url=required("DB_URL");verifyJdbcTarget(url,host);
   if(!user.equals(required("DB_USERNAME")))throw new IllegalStateException();
   Map<String,Object> forced=new HashMap<>();
   forced.put("spring.datasource.url",url);forced.put("spring.datasource.username",user);forced.put("spring.datasource.password",required("DB_PASSWORD"));
    if(mode==BootstrapMode.DEV_SUPERADMIN||mode==BootstrapMode.RESEND_SUPERADMIN){String encryptionKey=required("SIMERTPI_SMTP_ENCRYPTION_KEY");forced.put("simertpi.smtp.encryption-key",encryptionKey);}
   forced.put("spring.flyway.enabled",false);forced.put("spring.jpa.hibernate.ddl-auto","none");forced.put("spring.sql.init.mode","never");forced.put("spring.jpa.show-sql",false);forced.put("spring.jpa.properties.hibernate.show_sql",false);forced.put("logging.config","classpath:logback-bootstrap.xml");forced.put("spring.jpa.open-in-view",false);forced.put("logging.level.root","OFF");forced.put("spring.main.banner-mode","off");
   System.setProperty("logging.level.root","OFF");System.setProperty("logging.config","classpath:logback-bootstrap.xml");
   try(var context=new SpringApplicationBuilder(AdminBootstrapConfiguration.class).web(WebApplicationType.NONE).properties("spring.main.banner-mode=off","logging.level.root=OFF").initializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("bootstrap-safety",forced))).run()){
    verifyConnectedTarget(context.getBean(DataSource.class),host,user);
     var identity=new AdminBootstrapService.Identity(console.readLine("Usuario administrativo aprobado: "),console.readLine("Correo aprobado: "),console.readLine("Nombres: "),console.readLine("Apellidos: "));
      SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("BOOTSTRAP:"+operator,null,List.of(new SimpleGrantedAuthority("BOOTSTRAP_OPERATOR"))));
     if(mode==BootstrapMode.DEV_SUPERADMIN||mode==BootstrapMode.RESEND_SUPERADMIN)return runSuperadmin(context,console,identity,authorization,operator,mode);
    first=console.readPassword("Contraseña (mínimo 16 caracteres; máximo 72 bytes UTF-8): ");second=console.readPassword("Repita contraseña: ");
    if(first==null||second==null||!Arrays.equals(first,second))throw new IllegalArgumentException();
     try {context.getBean(AdminBootstrapService.class).createFirstAdmin(identity,CharBuffer.wrap(first),authorization);}
    catch(RuntimeException rejected){context.getBean(AuditService.class).recordOutcome("ADMIN_BOOTSTRAP","USER",null,"FAILURE",Map.of("approvalId",authorization));throw rejected;}
    console.printf("Cuenta administrativa creada y verificada; enabled=true, único rol=%s.%n",mode.roleCode);
   }
   return 0;
  }catch(Exception rejected){System.err.println("Bootstrap detenido; destino, autorización o persistencia no verificados. No se muestran detalles sensibles.");return 1;}
  finally {if(first!=null)Arrays.fill(first,'\0');if(second!=null)Arrays.fill(second,'\0');SecurityContextHolder.clearContext();}
 }
 private static int runSuperadmin(org.springframework.context.ConfigurableApplicationContext context,Console console,AdminBootstrapService.Identity identity,String authorization,String operator,BootstrapMode mode){
  String confirmation = console.readLine("Para confirmar el objetivo previsto, escriba CONFIRM DEV SUPERADMIN: ");
  if (!isApprovedDevSuperadmin(identity.username(), identity.email(), confirmation))
   throw new IllegalStateException("La identidad o confirmación no coincide con el Superadministrador DEV previsto; no se realizaron cambios");
  required("SIMERTPI_SMTP_ENCRYPTION_KEY");
  if(mode==BootstrapMode.DEV_SUPERADMIN){
   verifyPortalUrl(required("SIMERTPI_PUBLIC_BASE_URL"));
   char[] secret=console.readPassword("Gmail app password (hidden; not the account password): ");
   if(secret==null||secret.length==0)throw new IllegalArgumentException("SMTP credential required");
   try{
    var candidate=new SmtpCandidate("GMAIL","SIMERTPI \u2014 GAD Pi\u00f1as","antoxavie1992@gmail.com","smtp.gmail.com",587,"STARTTLS","antoxavie1992@gmail.com",secret);
    var created=context.getBean(AdminBootstrapService.class).createFirstSuperadminInvite(identity,authorization,candidate);
    var invite=created.invitation();
    var delivery=context.getBean(ActivationInvitationDeliveryService.class).deliver(invite.id(),invite.activationToken(),invite.expiresAt(),"BOOTSTRAP:"+operator);
    console.printf("Pending IT_ADMIN created; activation expires %s; provider status=%s (provider acceptance is not mailbox delivery).%n",invite.expiresAt(),delivery.status());
    return "ACCEPTED_BY_PROVIDER".equals(delivery.status())?0:2;
   }catch(RuntimeException rejected){context.getBean(AuditService.class).recordOutcome("ADMIN_BOOTSTRAP_PENDING","USER",null,"FAILURE",Map.of("approvalId",authorization));throw rejected;}
   finally{Arrays.fill(secret,(char)0);}
  }
  if(!context.getBean(SmtpConfigurationService.class).configured())throw new IllegalStateException("Verified active SMTP configuration required");
  var invite=context.getBean(AdminBootstrapService.class).reissueFirstSuperadminInvite(identity,authorization);
  var delivery=context.getBean(ActivationInvitationDeliveryService.class).deliver(invite.id(),invite.activationToken(),invite.expiresAt(),"BOOTSTRAP:"+operator);
  console.printf("Pending Superadministrator invitation reissued; provider status=%s.%n",delivery.status());
  return "ACCEPTED_BY_PROVIDER".equals(delivery.status())?0:2;
 }
 public static boolean isApprovedDevSuperadmin(String username, String email, String confirmation) {
  return DEV_SUPERADMIN_USERNAME.equals(username == null ? "" : username.trim())
      && DEV_SUPERADMIN_EMAIL.equalsIgnoreCase(email == null ? "" : email.trim())
      && DEV_SUPERADMIN_CONFIRMATION.equals(confirmation);
 }
 public enum BootstrapMode { DEV_ADMIN("SIMERTPI_ADMIN"), DEV_SUPERADMIN("IT_ADMIN"), RESEND_SUPERADMIN("IT_ADMIN"); final String roleCode; BootstrapMode(String roleCode){this.roleCode=roleCode;} }
 public static BootstrapMode parseMode(String[] args){if(args==null||args.length==0)throw new IllegalArgumentException();return switch(args[0]){case "--bootstrap-dev-admin"->BootstrapMode.DEV_ADMIN;case "--bootstrap-dev-superadmin"->BootstrapMode.DEV_SUPERADMIN;case "--resend-dev-superadmin-invitation"->BootstrapMode.RESEND_SUPERADMIN;default->throw new IllegalArgumentException();};}
 public static void verifyPortalUrl(String value){try{URI uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new IllegalArgumentException("Verified HTTPS portal origin required");}}
 public static void verifyJdbcTarget(String url,String expectedHost){
  if(expectedHost==null||!expectedHost.matches(java.util.regex.Pattern.quote(RESOURCE)+"(?:\\.[a-z]+-postgres\\.render\\.com|\\.internal)?"))throw new IllegalArgumentException("Unapproved DEV host");
  if(!url.startsWith("jdbc:postgresql://"))throw new IllegalArgumentException("Invalid JDBC target");
  URI uri=URI.create(url.substring(5));
  if(!expectedHost.equals(uri.getHost())||!"/simertpi".equals(uri.getPath())||uri.getUserInfo()!=null||uri.getPort()!=5432||uri.getFragment()!=null)throw new IllegalArgumentException("Target mismatch");
  var query=uri.getRawQuery();if(query==null||!Arrays.asList(query.split("&")).contains("sslmode=verify-full")||!Arrays.stream(query.split("&")).allMatch(q->q.equals("sslmode=verify-full")||q.startsWith("sslrootcert=")))throw new IllegalArgumentException("Verified TLS required");
 }
 public static void verifyConnectedTarget(DataSource source,String host,String user)throws Exception {
  try(var connection=source.getConnection()){
   verifyJdbcTarget(connection.getMetaData().getURL(),host);
   try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT current_database(),current_user,current_setting('server_version_num')::int, (SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid())")){
    if(!result.next()||!"simertpi".equals(result.getString(1))||!user.equals(result.getString(2))||result.getInt(3)/10000!=16||!result.getBoolean(4))throw new IllegalStateException("Connected target mismatch");
   }
  }
 }
 private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing configuration");return value;}
}
