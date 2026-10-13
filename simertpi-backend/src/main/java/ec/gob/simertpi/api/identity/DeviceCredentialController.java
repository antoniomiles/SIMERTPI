package ec.gob.simertpi.api.identity;

import ec.gob.simertpi.application.identity.auth.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/device")
public class DeviceCredentialController {
 private final DeviceCredentialService service;
 public DeviceCredentialController(DeviceCredentialService service){this.service=service;}
 public record Registration(@NotNull UUID deviceId,@NotBlank @Size(max=1024) String publicKey) {}
 public record Identity(@NotNull UUID credentialId) {}
 public record Verification(@NotNull UUID credentialId,@NotNull UUID challengeId,@NotBlank @Size(max=512) String signature) {}
 private String access(String header){if(header==null||!header.startsWith("Bearer "))throw new org.springframework.security.authentication.BadCredentialsException("Authentication required");return header.substring(7);}
 private <T> ResponseEntity<T> response(T body){return ResponseEntity.ok().header("Cache-Control","no-store").header("Pragma","no-cache").body(body);}
 @PostMapping("/register/challenge") public ResponseEntity<DeviceCredentialService.Challenge> register(@RequestHeader("Authorization") String bearer,@Valid @RequestBody Registration request){return response(service.startRegistration(access(bearer),request.deviceId(),request.publicKey()));}
 @PostMapping("/register/verify") public ResponseEntity<Void> registered(@RequestHeader("Authorization") String bearer,@Valid @RequestBody Verification request){service.finishRegistration(access(bearer),request.credentialId(),request.challengeId(),request.signature());return response(null);}
 @PostMapping("/challenge") public ResponseEntity<DeviceCredentialService.Challenge> challenge(@Valid @RequestBody Identity request){return response(service.loginChallenge(request.credentialId()));}
 @PostMapping("/authenticate") public ResponseEntity<MobileAuthService.SessionResponse> authenticate(@Valid @RequestBody Verification request){return response(service.authenticate(request.credentialId(),request.challengeId(),request.signature()));}
 @PostMapping("/unlock/challenge") public ResponseEntity<DeviceCredentialService.Challenge> unlockChallenge(@RequestHeader("Authorization") String bearer,@Valid @RequestBody Identity request){return response(service.unlockChallenge(access(bearer),request.credentialId()));}
 @PostMapping("/unlock/verify") public ResponseEntity<Void> unlock(@RequestHeader("Authorization") String bearer,@Valid @RequestBody Verification request){service.unlock(access(bearer),request.credentialId(),request.challengeId(),request.signature());return response(null);}
 @PostMapping("/revoke") public ResponseEntity<Void> revoke(@RequestHeader("Authorization") String bearer,@Valid @RequestBody Identity request){service.revoke(access(bearer),request.credentialId());return response(null);}
}
