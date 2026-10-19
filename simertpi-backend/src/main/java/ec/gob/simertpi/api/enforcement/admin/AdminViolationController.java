package ec.gob.simertpi.api.enforcement.admin;

import ec.gob.simertpi.api.enforcement.admin.dto.AdminViolationItem;
import ec.gob.simertpi.api.enforcement.admin.dto.AdminViolationPage;
import ec.gob.simertpi.application.enforcement.AdminViolationQueryService;
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
@RequestMapping("/api/v1/admin/violations")
public class AdminViolationController {
    private static final long MAX_RANGE_DAYS = 366;
    private static final Set<String> STATUSES = Set.of("OPEN", "NOTIFIED", "PAID", "CANCELLED", "DISPUTED");
    private final AdminViolationQueryService violations;

    public AdminViolationController(AdminViolationQueryService violations) {
        this.violations = violations;
    }

    @GetMapping
    public ResponseEntity<AdminViolationPage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String plate,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        validatePagination(page, size);
        validateLength("plate", plate, 10);
        validateLength("zone", zone, 100);
        validateLength("search", q, 100);
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        if (fromDate != null && toDate != null) {
            long days = ChronoUnit.DAYS.between(fromDate, toDate);
            if (fromDate.isAfter(toDate) || days >= MAX_RANGE_DAYS) {
                throw badRequest("Invalid date range");
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(violations.search(page, size, parseStatus(status), plate, zone, q, fromDate, toDate));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminViolationItem> detail(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(violations.findById(id));
    }

    private static void validatePagination(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw badRequest("Invalid pagination parameters");
        }
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

    private static String parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw badRequest("Invalid violation status");
        return status;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
