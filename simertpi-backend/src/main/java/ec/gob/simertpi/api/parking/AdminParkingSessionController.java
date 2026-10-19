package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.api.parking.dto.AdminParkingSessionPage;
import ec.gob.simertpi.application.parking.AdminParkingSessionQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/admin/parking/sessions")
public class AdminParkingSessionController {
    private final AdminParkingSessionQueryService sessions;

    public AdminParkingSessionController(AdminParkingSessionQueryService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public ResponseEntity<AdminParkingSessionPage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100 || (q != null && q.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination or search parameters");
        }
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
        }
        var parsedStatus = parseStatus(status);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sessions.search(page, size, parsedStatus, q, fromDate, toDate));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date");
        }
    }

    private static ec.gob.simertpi.domain.parking.entity.ParkingSessionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return ec.gob.simertpi.domain.parking.entity.ParkingSessionStatus.valueOf(
                    status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session status");
        }
    }
}
