package ec.gob.simertpi.api.enforcement.admin;

import ec.gob.simertpi.api.enforcement.admin.dto.AdminEvidenceItem;
import ec.gob.simertpi.application.enforcement.EvidenceService;
import ec.gob.simertpi.domain.enforcement.entity.Evidence;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminViolationEvidenceController {
    private final EvidenceService evidence;

    public AdminViolationEvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/violations/{violationId}/evidence")
    public ResponseEntity<List<AdminEvidenceItem>> listForViolation(
            @PathVariable UUID violationId, Authentication authentication) {
        List<AdminEvidenceItem> items = evidence.findByViolationId(authentication.getName(), violationId)
                .stream().map(AdminViolationEvidenceController::toItem).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(items);
    }

    @GetMapping("/evidence/{evidenceId}/content")
    public ResponseEntity<byte[]> download(
            @PathVariable UUID evidenceId, Authentication authentication) {
        EvidenceService.EvidenceDownload download = evidence.download(authentication.getName(), evidenceId);
        var object = download.object();
        String filename = safeDownloadFilename(download.evidence().getOriginalFilename());
        byte[] body;
        try (object) {
            body = object.stream().readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Evidence content could not be read", failure);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(object.contentType()))
                .contentLength(object.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private static AdminEvidenceItem toItem(Evidence item) {
        return new AdminEvidenceItem(item.getId(), item.getViolationId(), item.getEvidenceType(),
                item.getContentType(), item.getFileSize(), item.getCapturedAt(), item.getLatitude(),
                item.getLongitude(), item.getCreatedAt());
    }

    private static String safeDownloadFilename(String filename) {
        if (filename == null || filename.isBlank()) return "evidence";
        String basename = filename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return basename.isBlank() ? "evidence" : basename;
    }
}
