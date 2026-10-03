package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.domain.parking.entity.Street;
import ec.gob.simertpi.domain.parking.repository.StreetRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class StreetService {

    private final StreetRepository streetRepository;
    private final ZoneRepository zoneRepository;

    public StreetService(
            StreetRepository streetRepository,
            ZoneRepository zoneRepository) {
        this.streetRepository = streetRepository;
        this.zoneRepository = zoneRepository;
    }

    @Transactional(readOnly = true)
    public List<Street> findAll() {
        return streetRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Street> findByZoneIdAndCode(UUID zoneId, String code) {
        return streetRepository.findByZoneIdAndCode(zoneId, code);
    }

    @Transactional(readOnly = true)
    public List<Street> findByZoneId(UUID zoneId) {
        return streetRepository.findByZoneId(zoneId);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_STREET_CREATED", resourceType = "CONFIGURATION")
    public Street create(
            UUID zoneId,
            String code,
            String name,
            String description) {

        if (!zoneRepository.existsById(zoneId)) {
            throw new IllegalArgumentException("La zona no existe");
        }

        if (streetRepository.existsByZoneIdAndCode(zoneId, code)) {
            throw new IllegalArgumentException("El codigo de la calle ya existe en esta zona");
        }

        OffsetDateTime now = OffsetDateTime.now();

        Street street = new Street();
        street.setId(UUID.randomUUID());
        street.setZoneId(zoneId);
        street.setCode(code);
        street.setName(name);
        street.setDescription(description);
        street.setActive(true);
        street.setCreatedAt(now);
        street.setUpdatedAt(now);

        return streetRepository.save(street);
    }
}
