package ec.gob.simertpi.api.enforcement.admin;

import ec.gob.simertpi.api.enforcement.admin.dto.AdminInspectionDetail;
import ec.gob.simertpi.api.enforcement.admin.dto.AdminInspectionPage;
import ec.gob.simertpi.application.enforcement.AdminInspectionQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/inspections")
public class AdminInspectionController {
    private static final long MAX_RANGE_DAYS = 366;
    private static final Set<String> RESULTS = Set.of("VALID", "EXPIRED", "NO_PAYMENT", "VIOLATION");
    private final AdminInspectionQueryService inspections;

    public AdminInspectionController(AdminInspectionQueryService inspections) {
        this.inspections = inspections;
    }

    @GetMapping
    public ResponseEntity<AdminInspectionPage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String plate,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        validatePagination(page, size);
        validateLength("plate", plate, 10);
        validateLength("zone", zone, 100);
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        if (fromDate != null && toDate != null) {
            long days = ChronoUnit.DAYS.between(fromDate, toDate);
            if (fromDate.isAfter(toDate) || days >= MAX_RANGE_DAYS) throw badRequest("Invalid date range");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(inspections.search(page, size, plate, parseResult(result), zone, fromDate, toDate));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminInspectionDetail> detail(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inspections.findById(id));
    }

    private static void validatePagination(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) throw badRequest("Invalid pagination parameters");
    }

    private static void validateLength(String field, String value, int max) {
        if (value != null && value.length() > max) throw badRequest("Invalid " + field + " parameter");
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw badRequest("Invalid date");
        }
    }

    private static String parseResult(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!RESULTS.contains(normalized)) throw badRequest("Invalid inspection result");
        return normalized;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
