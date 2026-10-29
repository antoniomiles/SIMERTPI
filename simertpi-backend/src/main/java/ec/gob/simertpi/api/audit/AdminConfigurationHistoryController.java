package ec.gob.simertpi.api.audit;

import ec.gob.simertpi.application.audit.AdminConfigurationHistoryQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/configuration-history")
@Validated
public class AdminConfigurationHistoryController {
    private final AdminConfigurationHistoryQueryService history;

    public AdminConfigurationHistoryController(AdminConfigurationHistoryQueryService history) {
        this.history = history;
    }

    @GetMapping
    public ResponseEntity<List<AdminConfigurationHistoryQueryService.Change>> search(
            @RequestParam(defaultValue = "50") @Min(1) @Max(101) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.search(limit, offset));
    }
}
