package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.parking.repository.StreetRepository;
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

    public ParkingSpaceService(
            ParkingSpaceRepository parkingSpaceRepository,
            StreetRepository streetRepository) {
        this.parkingSpaceRepository = parkingSpaceRepository;
        this.streetRepository = streetRepository;
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
    @Audited(action = "CONFIGURATION_PARKING_SPACE_CREATED", resourceType = "CONFIGURATION")
    public ParkingSpace create(
            UUID streetId,
            String code,
            String qrCode,
            String spaceNumber,
            BigDecimal latitude,
            BigDecimal longitude,
            String spaceType) {

        if (!streetRepository.existsById(streetId)) {
            throw new IllegalArgumentException("La calle no existe");
        }

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

        OffsetDateTime now = OffsetDateTime.now();

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
