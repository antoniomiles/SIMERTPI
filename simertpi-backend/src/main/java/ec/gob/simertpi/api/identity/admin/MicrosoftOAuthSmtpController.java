package ec.gob.simertpi.api.identity.admin;

import ec.gob.simertpi.application.identity.smtp.MicrosoftOAuthSmtpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/system/email/oauth")
public final class MicrosoftOAuthSmtpController {
    private final MicrosoftOAuthSmtpService service;
    private final String portalOrigin;

    public MicrosoftOAuthSmtpController(MicrosoftOAuthSmtpService service,
                                        @Value("${simertpi.admin-auth.allowed-origin:}") String portalOrigin) {
        this.service = service; this.portalOrigin = portalOrigin == null ? "" : portalOrigin.trim();
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) { }

    @GetMapping("/status")
    public ResponseEntity<MicrosoftOAuthSmtpService.OAuthStatus> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(Authentication auth, HttpServletRequest request) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.start(auth.getName(), ec.gob.simertpi.config.AdminSessionFilter.cookie(request))); }
        catch (MicrosoftOAuthSmtpService.OAuthSmtpException failure) { return failure(failure); }
    }

    /** Narrow public callback: the service consumes a one-use state bound to an unexpired IT_ADMIN session. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error) {
        String result = service.complete(state, code, error);
        URI origin = securePortalOrigin(portalOrigin);
        URI location = origin.resolve("/admin/system/email?oauth=" + result);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("Referrer-Policy", "no-referrer").build();
    }

    private static URI securePortalOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
                throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("La URL segura del portal no está configurada");
        }
    }

    @PostMapping("/test-connection")
    public ResponseEntity<?> testConnection(Authentication auth) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.testConnection(auth.getName())); }
        catch (MicrosoftOAuthSmtpService.OAuthSmtpException failure) { return failure(failure); }
    }

    @PostMapping("/test-email")
    public ResponseEntity<?> testEmail(Authentication auth) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sendTestEmail(auth.getName())); }
        catch (MicrosoftOAuthSmtpService.OAuthSmtpException failure) { return failure(failure); }
    }

    @PostMapping("/activate")
    public ResponseEntity<?> activate(@Valid @RequestBody ReasonRequest request, Authentication auth) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.activate(auth.getName(), request.reason())); }
        catch (MicrosoftOAuthSmtpService.OAuthSmtpException failure) { return failure(failure); }
    }

    @PostMapping("/disconnect")
    public ResponseEntity<?> disconnect(@Valid @RequestBody ReasonRequest request, Authentication auth) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.disconnect(auth.getName(), request.reason())); }
        catch (MicrosoftOAuthSmtpService.OAuthSmtpException failure) { return failure(failure); }
    }

    private ResponseEntity<Map<String, Object>> failure(MicrosoftOAuthSmtpService.OAuthSmtpException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .body(Map.of("status", "FAILURE", "message", failure.getMessage()));
    }
}
