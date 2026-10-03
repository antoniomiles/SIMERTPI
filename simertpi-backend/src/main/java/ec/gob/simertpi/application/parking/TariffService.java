package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TariffService {

    private final TariffRepository tariffRepository;

    public TariffService(TariffRepository tariffRepository) {
        this.tariffRepository = tariffRepository;
    }

    @Transactional(readOnly = true)
    public List<Tariff> findAll() {
        return tariffRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Tariff findByCode(String code) {
        return tariffRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Tariff not found"));
    }

    @Transactional(readOnly = true)
    public List<Tariff> findActive() {
        return tariffRepository.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public List<Tariff> findActiveAt(OffsetDateTime dateTime) {
        return tariffRepository.findActiveTariffsAt(dateTime);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_TARIFF_CREATED", resourceType = "CONFIGURATION")
    public Tariff create(
            String code,
            String name,
            java.math.BigDecimal amount,
            Integer durationMinutes,
            Integer minMinutes,
            Integer maxContinuousMinutes,
            OffsetDateTime validFrom,
            OffsetDateTime validTo
    ) {
        if (tariffRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Tariff code already exists");
        }

        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }

        Tariff tariff = new Tariff();
        tariff.setId(UUID.randomUUID());
        tariff.setCode(code);
        tariff.setName(name);
        tariff.setAmount(amount);
        tariff.setDurationMinutes(durationMinutes);
        tariff.setMinMinutes(minMinutes);
        tariff.setMaxContinuousMinutes(maxContinuousMinutes);
        tariff.setActive(true);
        tariff.setValidFrom(validFrom);
        tariff.setValidTo(validTo);

        OffsetDateTime now = OffsetDateTime.now();
        tariff.setCreatedAt(now);
        tariff.setUpdatedAt(now);

        return tariffRepository.save(tariff);
    }
}
