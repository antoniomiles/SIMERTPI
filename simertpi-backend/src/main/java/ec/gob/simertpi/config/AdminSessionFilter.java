package ec.gob.simertpi.config;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.application.identity.admin.AdminSessionExpiredAuthenticationException;
import ec.gob.simertpi.api.OperationalRequestLogFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
public class AdminSessionFilter extends OncePerRequestFilter {
 private final AdminAuthenticationService auth;private final String allowedOrigin;
 public AdminSessionFilter(AdminAuthenticationService auth,String allowedOrigin){this.auth=auth;this.allowedOrigin=allowedOrigin;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
  response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");
  String origin=request.getHeader("Origin");
  String actual=request.getScheme()+"://"+request.getServerName()+((request.getServerPort()==443&&request.isSecure())||(request.getServerPort()==80&&!request.isSecure())?"":":"+request.getServerPort());
  String path=request.getRequestURI();
  boolean microsoftOAuthCallback="GET".equals(request.getMethod())&&path.equals("/api/v1/admin/system/email/oauth/callback");
  if(!microsoftOAuthCallback&&("cross-site".equals(request.getHeader("Sec-Fetch-Site"))||(origin!=null&&!origin.equals(allowedOrigin.isBlank()?actual:allowedOrigin)))){reject(request,response,403,"ORIGIN_REJECTED");return;}
  if(request.getHeader("Authorization")!=null){reject(request,response,401,"AUTHORIZATION_HEADER_REJECTED");return;}
  boolean publicRoute=path.equals("/api/v1/admin/auth/login")||path.equals("/api/v1/admin/auth/csrf")||path.equals("/api/v1/admin/auth/logout")||microsoftOAuthCallback;
  if(!publicRoute){
   String sessionCookie=cookie(request);
   try { request.setAttribute("admin.identity",auth.authenticate(sessionCookie)); }
   catch(AdminSessionExpiredAuthenticationException expired){SecurityContextHolder.clearContext();reject(request,response,401,"SESSION_EXPIRED");return;}
   catch(AuthenticationException invalid){SecurityContextHolder.clearContext();reject(request,response,401,sessionCookie==null?"SESSION_MISSING":"SESSION_INVALID");return;}
  }
  chain.doFilter(request,response);
 }
 private static void reject(HttpServletRequest request,HttpServletResponse response,int status,String reason)throws IOException {
  OperationalRequestLogFilter.mark(request,reason,null);error(response,status);
 }
 public static String cookie(HttpServletRequest request){return request.getCookies()==null?null:Arrays.stream(request.getCookies()).filter(c->c.getName().equals(AdminAuthenticationService.COOKIE)).map(Cookie::getValue).findFirst().orElse(null);}
 public static void error(HttpServletResponse response,int status)throws IOException {response.setStatus(status);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"status\":"+status+",\"message\":\"Administrative access rejected\"}");}
}
