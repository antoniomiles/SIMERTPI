package ec.gob.simertpi.api.identity.admin;

import ec.gob.simertpi.application.identity.smtp.SmtpCandidate;
import ec.gob.simertpi.application.identity.smtp.SmtpConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/system/email")
@Validated
public class AdminSmtpConfigurationController {
    private final SmtpConfigurationService service;

    public AdminSmtpConfigurationController(SmtpConfigurationService service) { this.service = service; }

    public record DraftRequest(
            @NotBlank @Pattern(regexp = "GMAIL|CUSTOM") String provider,
            @NotBlank @Size(max = 150) String displayName,
            @NotBlank @jakarta.validation.constraints.Email @Size(max = 255) String from,
            @NotBlank @Size(max = 253) String host,
            @Min(1) @Max(65535) int port,
            @NotBlank @Pattern(regexp = "STARTTLS|SMTPS") String security,
            @NotBlank @Size(max = 255) String username,
            @Size(max = 512) char[] password,
            @NotBlank @Size(max = 500) String reason) {
        public SmtpCandidate candidate() { return new SmtpCandidate(provider, displayName, from, host, port, security, username, password); }
        @Override public String toString() { return "DraftRequest[provider="+provider+", host="+host+", port="+port+", password=redacted]"; }
    }
    public record VersionRequest(@NotNull Long version) { }
    public record ActivateRequest(@NotNull Long version, @NotBlank @Size(max=500) String reason) { }
    public record DisableRequest(@NotBlank @Size(max=500) String reason) { }

    @GetMapping
    public ResponseEntity<SmtpConfigurationService.Snapshot> configuration() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getConfiguration());
    }

    @PutMapping("/draft")
    public ResponseEntity<?> saveDraft(@Valid @RequestBody DraftRequest request, Authentication auth) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(service.saveDraft(request.candidate(), auth.getName(), request.reason()));
        } finally { if (request.password() != null) Arrays.fill(request.password(), '\0'); }
    }

    @PostMapping("/test-connection")
    public ResponseEntity<?> testConnection(@Valid @RequestBody VersionRequest request, Authentication auth) {
        try {
            var result = service.testConnection(request.version(), auth.getName());
            if ("FAILURE".equals(result.status())) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", "FAILURE", "version", result.version(), "message", "No fue posible conectar con el servidor SMTP. Verifica host, puerto, TLS y credenciales."));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        } catch (ec.gob.simertpi.application.identity.smtp.SmtpOperationException failure) { return failure(failure); }
    }

    @PostMapping("/test-email")
    public ResponseEntity<?> testEmail(Authentication auth) {
        try {
            var result = service.sendTestEmail(auth.getName());
            if ("FAILURE".equals(result.status())) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", "FAILURE", "version", result.version(), "message", "El proveedor no aceptó el correo al destinatario de prueba configurado."));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        } catch (ec.gob.simertpi.application.identity.smtp.SmtpOperationException failure) { return failure(failure); }
    }

    @PostMapping("/activate")
    public ResponseEntity<?> activate(@Valid @RequestBody ActivateRequest request, Authentication auth) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.activate(request.version(), request.reason(), auth.getName())); }
        catch (ec.gob.simertpi.application.identity.smtp.SmtpOperationException failure) { return failure(failure); }
    }

    @PostMapping("/disable")
    public ResponseEntity<?> disable(@Valid @RequestBody DisableRequest request, Authentication auth) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.disable(request.reason(), auth.getName())); }
        catch (ec.gob.simertpi.application.identity.smtp.SmtpOperationException failure) { return failure(failure); }
    }

    private ResponseEntity<Map<String, Object>> failure(ec.gob.simertpi.application.identity.smtp.SmtpOperationException failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).body(Map.of(
                "status", "FAILURE", "message", failure.getMessage()));
    }
}
