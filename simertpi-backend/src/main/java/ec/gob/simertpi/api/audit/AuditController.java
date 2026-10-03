package ec.gob.simertpi.api.audit;

import ec.gob.simertpi.domain.audit.AuditSearchRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditSearchRepository audit;

    public AuditController(AuditSearchRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditSearchRepository.AuditRow> search(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String correlationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || (from != null && to != null && from.isAfter(to))) {
            throw new IllegalArgumentException("Invalid audit search range");
        }
        return audit.search(actorId, bounded(action, 120), bounded(resourceType, 80), resourceId,
                from, to, bounded(result, 20), bounded(correlationId, 128), limit, offset);
    }

    private String bounded(String value, int maxLength) {
        if (value != null && value.length() > maxLength) throw new IllegalArgumentException("Audit filter is too long");
        return value;
    }
}
