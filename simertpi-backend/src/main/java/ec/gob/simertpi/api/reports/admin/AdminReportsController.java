package ec.gob.simertpi.api.reports.admin;

import ec.gob.simertpi.api.reports.admin.dto.AdminReportResponse;
import ec.gob.simertpi.application.reports.AdminReportsQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportsController {
    private static final long MAX_RANGE_DAYS = 366;
    private final AdminReportsQueryService reports;

    public AdminReportsController(AdminReportsQueryService reports) {
        this.reports = reports;
    }

    @GetMapping
    public ResponseEntity<AdminReportResponse> get(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)
                || ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid date range of at most 366 days is required");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reports.load(fromDate, toDate));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date");
        }
    }
}
