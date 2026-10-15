package ec.gob.simertpi.api.identity;

import ec.gob.simertpi.application.identity.auth.MunicipalAuthService;
import ec.gob.simertpi.application.identity.auth.MobileAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/municipal/auth")
public class MunicipalAuthController {
    private final MunicipalAuthService service;

    public MunicipalAuthController(MunicipalAuthService service) {
        this.service = service;
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank String password) {
        @Override public String toString() { return "MunicipalLoginRequest[redacted]"; }
    }

    public record RefreshRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {
        @Override public String toString() { return "MunicipalRefreshRequest[redacted]"; }
    }

    @PostMapping("/login")
    public ResponseEntity<MobileAuthService.SessionResponse> login(@Valid @RequestBody LoginRequest request) {
        return session(service.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<MobileAuthService.SessionResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return session(service.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        service.logout(request.refreshToken());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping("/me")
    public ResponseEntity<MunicipalAuthService.IdentityResponse> me(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new org.springframework.security.authentication.BadCredentialsException("Authentication required");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache").body(service.me(authorization.substring(7)));
    }

    private ResponseEntity<MobileAuthService.SessionResponse> session(MobileAuthService.SessionResponse body) {
        return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
