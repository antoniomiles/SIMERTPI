package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ZoneService {

    private final ZoneRepository zoneRepository;

    public ZoneService(ZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    @Transactional(readOnly = true)
    public List<Zone> findAll() {
        return zoneRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Zone> findByCode(String code) {
        return zoneRepository.findByCode(code);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_ZONE_CREATED", resourceType = "CONFIGURATION")
    public Zone create(String code, String name, String description) {

        if (zoneRepository.existsByCode(code)) {
            throw new IllegalArgumentException("El código de la zona ya existe");
        }

        OffsetDateTime now = OffsetDateTime.now();

        Zone zone = new Zone();
        zone.setId(UUID.randomUUID());
        zone.setCode(code);
        zone.setName(name);
        zone.setDescription(description);
        zone.setActive(true);
        zone.setCreatedAt(now);
        zone.setUpdatedAt(now);

        return zoneRepository.save(zone);
    }
}
