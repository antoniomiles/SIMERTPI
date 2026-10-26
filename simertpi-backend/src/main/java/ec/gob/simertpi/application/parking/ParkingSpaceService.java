package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.entity.Street;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.parking.repository.StreetRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ParkingSpaceService {

    private final ParkingSpaceRepository parkingSpaceRepository;
    private final StreetRepository streetRepository;
    private final ZoneRepository zoneRepository;

    public ParkingSpaceService(
            ParkingSpaceRepository parkingSpaceRepository,
            StreetRepository streetRepository,
            ZoneRepository zoneRepository) {
        this.parkingSpaceRepository = parkingSpaceRepository;
        this.streetRepository = streetRepository;
        this.zoneRepository = zoneRepository;
    }

    @Transactional(readOnly = true)
    public List<ParkingSpace> findAll() {
        return parkingSpaceRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<ParkingSpace> findByCode(String code) {
        return parkingSpaceRepository.findByCode(code);
    }

    @Transactional(readOnly = true)
    public Optional<ParkingSpace> findByQrCode(String qrCode) {
        return parkingSpaceRepository.findByQrCode(qrCode);
    }

    @Transactional(readOnly = true)
    public List<ParkingSpace> findByStreetId(UUID streetId) {
        return parkingSpaceRepository.findByStreetId(streetId);
    }

    @Transactional
    @Audited(action = "CONFIGURATION_PARKING_SPACE_CREATED", resourceType = "PARKING_SPACE")
    public ParkingSpace create(
            UUID streetId,
            String code,
            String qrCode,
            String spaceNumber,
            BigDecimal latitude,
            BigDecimal longitude,
            String spaceType) {

        Street initial = streetRepository.findById(streetId)
                .orElseThrow(() -> new ec.gob.simertpi.api.ResourceNotFoundException("La calle no existe"));
        var zone = zoneRepository.findByIdForUpdate(initial.getZoneId())
                .orElseThrow(() -> new ec.gob.simertpi.api.ResourceNotFoundException("La zona no existe"));
        Street street = streetRepository.findByIdForUpdate(streetId)
                .orElseThrow(() -> new ec.gob.simertpi.api.ResourceNotFoundException("La calle no existe"));
        if (!street.getZoneId().equals(zone.getId())) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "La relación entre calle y zona cambió. Actualice el registro.");
        if (!zone.isActive() || !street.isActive()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "No se puede registrar un espacio en una calle o zona deshabilitada.");

        if (parkingSpaceRepository.existsByCode(code)) {
            throw new IllegalArgumentException("El codigo del espacio ya existe");
        }

        if (parkingSpaceRepository.existsByQrCode(qrCode)) {
            throw new IllegalArgumentException("El QR del espacio ya existe");
        }

        if (parkingSpaceRepository.existsByStreetIdAndSpaceNumber(streetId, spaceNumber)) {
            throw new IllegalArgumentException(
                    "El numero de espacio ya existe en esta calle"
            );
        }

        OffsetDateTime now = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        ParkingSpace parkingSpace = new ParkingSpace();
        parkingSpace.setId(UUID.randomUUID());
        parkingSpace.setStreetId(streetId);
        parkingSpace.setCode(code);
        parkingSpace.setQrCode(qrCode);
        parkingSpace.setSpaceNumber(spaceNumber);
        parkingSpace.setLatitude(latitude);
        parkingSpace.setLongitude(longitude);
        parkingSpace.setSpaceType(
                spaceType == null || spaceType.isBlank()
                        ? "STANDARD"
                        : spaceType
        );
        parkingSpace.setActive(true);
        parkingSpace.setCreatedAt(now);
        parkingSpace.setUpdatedAt(now);

        return parkingSpaceRepository.save(parkingSpace);
    }
}
