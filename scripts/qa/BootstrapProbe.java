import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
/** Isolated fixture utility, never included in the backend artifact. */
public class BootstrapProbe {
 public static void main(String[] args)throws Exception {
  String url=System.getenv("DB_URL"),user=System.getenv("DB_USERNAME"),password=System.getenv("DB_PASSWORD");
  if(!url.contains("dpg-db1hq55g1s2s73a8e340-a.oregon-postgres.render.com:5432/simertpi?sslmode=verify-full"))throw new IllegalStateException();
  if(args[0].equals("init")){
   Flyway.configure().dataSource(url,user,password).target("41").load().migrate();
   try(Connection c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
    s.execute("CREATE ROLE bootstrap_qa LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '"+password+"'"); s.execute("GRANT USAGE ON SCHEMA identity,audit TO bootstrap_qa"); s.execute("GRANT SELECT ON identity.users,identity.roles,identity.user_roles TO bootstrap_qa"); s.execute("GRANT INSERT ON identity.users,identity.user_roles,audit.functional_audit_log TO bootstrap_qa");
   }return;
  }
  try(Connection c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
   if(args[0].equals("empty")){try(var r=s.executeQuery("SELECT (SELECT count(*) FROM identity.users)+(SELECT count(*) FROM audit.functional_audit_log WHERE action='ADMIN_BOOTSTRAP')")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Unexpected write after TLS rejection");}return;} try(var r=s.executeQuery("SELECT count(*) FROM identity.users")){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("Unexpected account mutation");}
   try(var r=s.executeQuery("SELECT u.enabled,u.password_hash,r.code FROM identity.users u JOIN identity.user_roles ur ON ur.user_id=u.id JOIN identity.roles r ON r.id=ur.role_id")){if(!r.next()||!r.getBoolean(1)||!new BCryptPasswordEncoder().matches(System.getenv("QA_ADMIN_PASSWORD"),r.getString(2))||!r.getString(3).equals("SIMERTPI_ADMIN")||r.next())throw new IllegalStateException("Account invariant");}
   try(var r=s.executeQuery("SELECT count(*) FROM public.flyway_schema_history")){r.next();if(r.getInt(1)!=40)throw new IllegalStateException("Unexpected migration");}
   try(var r=s.executeQuery("SELECT count(*) FROM audit.functional_audit_log WHERE action='ADMIN_BOOTSTRAP' AND result='SUCCESS'")){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("Missing success audit");}
   try(var r=s.executeQuery("SELECT to_regclass('identity.admin_web_sessions')")){r.next();if(r.getString(1)!=null)throw new IllegalStateException("Bootstrap applied V42");} try(var r=s.executeQuery("SELECT count(*) FROM audit.functional_audit_log WHERE action='ADMIN_BOOTSTRAP' AND result='FAILURE'")){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("Missing rejection audit");} try(var r=s.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")){if(!r.next()||!r.getBoolean(1))throw new IllegalStateException("TLS required");}
  }
  try(Connection c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT has_table_privilege('bootstrap_qa','identity.users','UPDATE') OR has_table_privilege('bootstrap_qa','identity.users','DELETE') OR has_schema_privilege('bootstrap_qa','identity','CREATE')")){r.next();if(r.getBoolean(1))throw new IllegalStateException("Excess QA bootstrap privileges");} System.out.println("PASS: isolated account, BCrypt, role, audit, no repeated migration, TLS");
 }
}
