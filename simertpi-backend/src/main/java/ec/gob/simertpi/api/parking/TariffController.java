package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.api.parking.dto.CreateTariffRequest;
import ec.gob.simertpi.api.parking.dto.TariffResponse;
import ec.gob.simertpi.application.parking.TariffService;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tariffs")
public class TariffController {

    private final TariffService tariffService;

    public TariffController(TariffService tariffService) {
        this.tariffService = tariffService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TariffResponse create(@Valid @RequestBody CreateTariffRequest request) {

        Tariff tariff = tariffService.create(
                request.code(),
                request.name(),
                request.amount(),
                request.durationMinutes(),
                request.minMinutes(),
                request.maxContinuousMinutes(),
                request.validFrom(),
                request.validTo()
        );

        return toResponse(tariff);
    }

    @GetMapping
    public List<TariffResponse> findAll() {
        return tariffService.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/active")
    public List<TariffResponse> findActive() {
        return tariffService.findActive()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/code/{code}")
    public TariffResponse findByCode(@PathVariable String code) {
        return toResponse(tariffService.findByCode(code));
    }

    @GetMapping("/active-at")
    public List<TariffResponse> findActiveAt(
            @RequestParam OffsetDateTime dateTime
    ) {
        return tariffService.findActiveAt(dateTime)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private TariffResponse toResponse(Tariff tariff) {
        return new TariffResponse(
                tariff.getId(),
                tariff.getCode(),
                tariff.getName(),
                tariff.getAmount(),
                tariff.getDurationMinutes(),
                tariff.getMinMinutes(),
                tariff.getMaxContinuousMinutes(),
                tariff.isActive(),
                tariff.getValidFrom(),
                tariff.getValidTo(),
                tariff.getCreatedAt(),
                tariff.getUpdatedAt()
        );
    }
}