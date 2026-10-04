package ec.gob.simertpi.api.identity;
import ec.gob.simertpi.application.identity.auth.MobileAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class MobileAuthController {
 private final MobileAuthService service;
 public MobileAuthController(MobileAuthService service){this.service=service;}
 public record LoginRequest(@NotBlank @Size(max=100) String username,@NotBlank String password){
  @Override public String toString(){return "LoginRequest[redacted]";}
 }
 public record RefreshRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String refreshToken){
  @Override public String toString(){return "RefreshRequest[redacted]";}
 }
 @PostMapping("/login") public ResponseEntity<MobileAuthService.SessionResponse> login(@Valid @RequestBody LoginRequest request){return tokens(service.login(request.username(),request.password()));}
 @PostMapping("/refresh") public ResponseEntity<MobileAuthService.SessionResponse> refresh(@Valid @RequestBody RefreshRequest request){return tokens(service.refresh(request.refreshToken()));}
 @PostMapping("/logout") public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request){service.logout(request.refreshToken());return ResponseEntity.noContent().header("Cache-Control","no-store").build();}
 private ResponseEntity<MobileAuthService.SessionResponse> tokens(MobileAuthService.SessionResponse session){return ResponseEntity.ok().header("Cache-Control","no-store").header("Pragma","no-cache").body(session);}
}
