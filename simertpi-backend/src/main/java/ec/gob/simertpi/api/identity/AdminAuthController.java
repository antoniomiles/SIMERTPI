package ec.gob.simertpi.api.identity;
import ec.gob.simertpi.application.identity.admin.AdminAuthenticationService;
import ec.gob.simertpi.config.AdminSessionFilter;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {
 private final AdminAuthenticationService auth;private final CookieCsrfTokenRepository csrf;
 public AdminAuthController(AdminAuthenticationService auth,CookieCsrfTokenRepository csrf){this.auth=auth;this.csrf=csrf;}
 public record Login(@NotBlank @Size(max=100) String username,@NotBlank @Size(max=256) String password){@Override public String toString(){return "AdminLogin[redacted]";}}
 @GetMapping("/csrf") public ResponseEntity<Void> csrf(CsrfToken token){token.getToken();return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
 @PostMapping("/login") public ResponseEntity<AdminAuthenticationService.Identity> login(@Valid @RequestBody Login body,HttpServletRequest request,HttpServletResponse response){
  var issued=auth.login(body.username(),body.password(),AdminSessionFilter.cookie(request),request.getRemoteAddr());
  response.addHeader(HttpHeaders.SET_COOKIE,cookie(issued.opaque(),false).toString());csrf.saveToken(csrf.generateToken(request),request,response);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued.identity());
 }
 @GetMapping("/me") public ResponseEntity<AdminAuthenticationService.Identity> me(HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body((AdminAuthenticationService.Identity)request.getAttribute("admin.identity"));}
 @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request,HttpServletResponse response){auth.logout(AdminSessionFilter.cookie(request));response.addHeader(HttpHeaders.SET_COOKIE,cookie("",true).toString());csrf.saveToken(null,request,response);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
 @ExceptionHandler(AuthenticationException.class) public ResponseEntity<java.util.Map<String,Object>> rejected(){return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).body(java.util.Map.of("status",401,"message","Acceso administrativo rechazado"));}
 private static ResponseCookie cookie(String value,boolean delete){var builder=ResponseCookie.from(AdminAuthenticationService.COOKIE,value).httpOnly(true).secure(true).sameSite("Strict").path("/");if(delete)builder.maxAge(0);return builder.build();}
}
