package ec.gob.simertpi.config;
import ec.gob.simertpi.application.identity.auth.MobileAuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

public class MobileBearerFilter extends OncePerRequestFilter {
 private final MobileAuthService service;
 public MobileBearerFilter(MobileAuthService service){this.service=service;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String header=request.getHeader("Authorization");
  if(header!=null&&header.regionMatches(true,0,"Bearer ",0,7)) {
   try {
    var user=service.authenticate(header.substring(7));
    var authentication=UsernamePasswordAuthenticationToken.authenticated(user,null,user.getAuthorities());
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    new org.springframework.security.web.context.RequestAttributeSecurityContextRepository().saveContext(context,request,response);
   } catch(AuthenticationException exception){
    SecurityContextHolder.clearContext();response.setStatus(401);response.setContentType("application/json");
    response.getWriter().write("{\"status\":401,\"message\":\"Authentication required\"}");return;
   }
  }
  chain.doFilter(request,response);
 }
}
