package ec.gob.simertpi.config;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.context.SecurityContextHolderFilter;
@Configuration(proxyBeanMethods=false)
public class AdminSecurityConfiguration {
 @Bean public CookieCsrfTokenRepository adminCsrfRepository(){
  var repo=CookieCsrfTokenRepository.withHttpOnlyFalse();repo.setCookieName("__Host-SIMERTPI_ADMIN_XSRF");repo.setCookiePath("/");
  repo.setCookieCustomizer(c->c.secure(true).httpOnly(false).sameSite("Strict"));return repo;
 }
 @Bean @Order(1) public SecurityFilterChain administrativeSecurity(HttpSecurity http,AdminAuthenticationService auth,CookieCsrfTokenRepository adminCsrfRepository,
  ec.gob.simertpi.application.audit.AuditService audit,
  @Value("${simertpi.admin-auth.allowed-origin:}") String origin)throws Exception {
  // Retain XOR masking for request attributes, resolve Angular's raw CSRF header only.
  var xor=new XorCsrfTokenRequestAttributeHandler();
  CsrfTokenRequestHandler spa=new CsrfTokenRequestHandler(){
   public void handle(jakarta.servlet.http.HttpServletRequest req,jakarta.servlet.http.HttpServletResponse res,java.util.function.Supplier<CsrfToken> token){xor.handle(req,res,token);}
   public String resolveCsrfTokenValue(jakarta.servlet.http.HttpServletRequest req,CsrfToken token){return req.getHeader(token.getHeaderName());}
  };
  http.securityMatcher("/api/v1/admin/**")
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable())
   .httpBasic(c->c.disable()).formLogin(c->c.disable()).logout(c->c.disable())
   .csrf(c->c.csrfTokenRepository(adminCsrfRepository).csrfTokenRequestHandler(spa))
   .exceptionHandling(e->e.authenticationEntryPoint((q,s,x)->AdminSessionFilter.error(s,401)).accessDeniedHandler((q,s,x)->{audit.recordOutcome("ADMIN_ACCESS_REJECTED","ADMIN_SESSION",null,"FAILURE",java.util.Map.of("reason","CSRF_OR_PERMISSION"));AdminSessionFilter.error(s,403);}))
   .authorizeHttpRequests(a->a
    .requestMatchers(HttpMethod.GET,"/api/v1/admin/auth/csrf").permitAll()
    .requestMatchers(HttpMethod.POST,"/api/v1/admin/auth/login","/api/v1/admin/auth/logout").permitAll()
    .requestMatchers(HttpMethod.GET,"/api/v1/admin/auth/me","/api/v1/admin/parking/reentry-configuration").hasAuthority("SIMERTPI_ADMIN")
    .requestMatchers(HttpMethod.PUT,"/api/v1/admin/parking/reentry-configuration").hasAuthority("SIMERTPI_ADMIN")
    .requestMatchers(HttpMethod.POST,"/api/v1/admin/reconciliation/payments","/api/v1/admin/reconciliation/evidence","/api/v1/admin/reconciliation/outbox").hasAuthority("SIMERTPI_ADMIN")
    .anyRequest().denyAll())
   .addFilterAfter(new AdminSessionFilter(auth,origin),SecurityContextHolderFilter.class);
  return http.build();
 }
}
