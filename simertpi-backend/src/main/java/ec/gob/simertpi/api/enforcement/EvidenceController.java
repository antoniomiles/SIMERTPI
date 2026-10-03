package ec.gob.simertpi.api.enforcement;

import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.enforcement.dto.EvidenceResponse;
import ec.gob.simertpi.application.enforcement.EvidenceService;
import ec.gob.simertpi.application.enforcement.EnforcementIdempotencyService;
import ec.gob.simertpi.domain.enforcement.entity.Evidence;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;
    private final EnforcementIdempotencyService idempotencyService;

    public EvidenceController(EvidenceService evidenceService,
                              EnforcementIdempotencyService idempotencyService) {
        this.evidenceService = evidenceService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EvidenceResponse> create(
            @RequestParam UUID violationId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) OffsetDateTime capturedAt,
            @RequestParam(required = false) java.math.BigDecimal latitude,
            @RequestParam(required = false) java.math.BigDecimal longitude,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        if (file == null) throw new InvalidRequestException("Evidence file is required");
        try {
            Evidence evidence = idempotencyService.createEvidenceUpload(authentication.getName(), idempotencyKey,
                    violationId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), capturedAt,
                    latitude, longitude);
            return ResponseEntity.status(201).body(toResponse(evidence));
        } catch (IOException readFailure) {
            throw new InvalidRequestException("Evidence file could not be read");
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<EvidenceResponse> findById(
            @PathVariable UUID id, Authentication authentication
    ) {
        return ResponseEntity.ok(toResponse(evidenceService.findById(authentication.getName(), id)));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable UUID id, Authentication authentication
    ) {
        EvidenceService.EvidenceDownload download = evidenceService.download(authentication.getName(), id);
        var object = download.object();
        String filename = safeDownloadFilename(download.evidence().getOriginalFilename());
        StreamingResponseBody body = output -> {
            try (object) {
                object.stream().transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(object.contentType()))
                .contentLength(object.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    @GetMapping("/violation/{violationId}")
    public ResponseEntity<List<EvidenceResponse>> findByViolationId(
            @PathVariable UUID violationId, Authentication authentication
    ) {
        return ResponseEntity.ok(evidenceService.findByViolationId(authentication.getName(), violationId).stream()
                .map(this::toResponse).toList());
    }

    private String safeDownloadFilename(String filename) {
        if (filename == null || filename.isBlank()) return "evidence";
        String basename = filename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return basename.isBlank() ? "evidence" : basename;
    }

    private EvidenceResponse toResponse(Evidence evidence) {
        return new EvidenceResponse(evidence.getId(), evidence.getViolationId(), evidence.getEvidenceType(),
                evidence.getOriginalFilename(), evidence.getContentType(), evidence.getFileSize(),
                evidence.getSha256Hash(), evidence.getCapturedAt(), evidence.getLatitude(),
                evidence.getLongitude(), evidence.getCreatedAt());
    }
}
