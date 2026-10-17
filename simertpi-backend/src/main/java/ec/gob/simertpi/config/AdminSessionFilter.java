package ec.gob.simertpi.config;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
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
  if("cross-site".equals(request.getHeader("Sec-Fetch-Site"))||(origin!=null&&!origin.equals(allowedOrigin.isBlank()?actual:allowedOrigin))){error(response,403);return;}
  if(request.getHeader("Authorization")!=null){error(response,401);return;}
  String path=request.getRequestURI();
  boolean publicRoute=path.equals("/api/v1/admin/auth/login")||path.equals("/api/v1/admin/auth/csrf")||path.equals("/api/v1/admin/auth/logout");
  if(!publicRoute){
   try { request.setAttribute("admin.identity",auth.authenticate(cookie(request))); }
   catch(AuthenticationException invalid){SecurityContextHolder.clearContext();error(response,401);return;}
  }
  chain.doFilter(request,response);
 }
 public static String cookie(HttpServletRequest request){return request.getCookies()==null?null:Arrays.stream(request.getCookies()).filter(c->c.getName().equals(AdminAuthenticationService.COOKIE)).map(Cookie::getValue).findFirst().orElse(null);}
 public static void error(HttpServletResponse response,int status)throws IOException {response.setStatus(status);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"status\":"+status+",\"message\":\"Administrative access rejected\"}");}
}
