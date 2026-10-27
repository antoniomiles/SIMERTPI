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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

        validateCoordinates(latitude, longitude);

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

        String normalizedCode = code.trim();
        if (parkingSpaceRepository.existsByCode(normalizedCode)) {
            throw new IllegalArgumentException("El codigo del espacio ya existe");
        }

        String normalizedNumber = spaceNumber.trim();
        if (parkingSpaceRepository.existsByStreetIdAndSpaceNumber(streetId, normalizedNumber)) {
            throw new IllegalArgumentException(
                    "El numero de espacio ya existe en esta calle"
            );
        }

        OffsetDateTime now = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        ParkingSpace parkingSpace = new ParkingSpace();
        UUID id = UUID.randomUUID();
        parkingSpace.setId(id);
        parkingSpace.setStreetId(streetId);
        parkingSpace.setCode(normalizedCode);
        String normalizedQr = qrCode == null || qrCode.isBlank() ? "SIMERTPI:SPACE:" + id : qrCode.trim();
        if (parkingSpaceRepository.existsByQrCode(normalizedQr)) {
            throw new IllegalArgumentException("El QR del espacio ya existe");
        }
        parkingSpace.setQrCode(normalizedQr);
        parkingSpace.setSpaceNumber(normalizedNumber);
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

        rejectDuplicateCoordinates(latitude, longitude, id);
        try {
            return parkingSpaceRepository.saveAndFlush(parkingSpace);
        } catch (DataIntegrityViolationException exception) {
            throw translateUniqueConflict(exception);
        }
    }

    private void validateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("Ingrese latitud y longitud juntas, o deje ambas vacías.");
        }
        if (latitude == null) return;
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Las coordenadas deben estar dentro del rango válido de latitud y longitud.");
        }
        if (Math.max(latitude.scale(), longitude.scale()) > 7) {
            throw new IllegalArgumentException("Las coordenadas admiten hasta 7 decimales.");
        }
    }

    private void rejectDuplicateCoordinates(BigDecimal latitude, BigDecimal longitude, UUID id) {
        if (latitude == null) return;
        parkingSpaceRepository.findFirstByLatitudeAndLongitudeAndIdNot(latitude, longitude, id)
                .ifPresent(existing -> { throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Estas coordenadas ya están registradas en el espacio " + existing.getCode() + "."); });
    }

    private RuntimeException translateUniqueConflict(DataIntegrityViolationException exception) {
        String detail = exception.getMostSpecificCause().getMessage();
        if (detail != null && detail.contains("uq_parking_spaces_coordinates")) {
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "Estas coordenadas ya están registradas en otro espacio. Revise la ubicación seleccionada.", exception);
        }
        if (detail != null && detail.contains("uk_parking_spaces_qr")) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "El identificador QR ya existe.", exception);
        }
        if (detail != null && detail.contains("uk_parking_spaces_code")) return new ResponseStatusException(HttpStatus.CONFLICT,"El código del espacio ya existe.",exception);
        if (detail != null && detail.contains("uk_parking_spaces_street_number")) return new ResponseStatusException(HttpStatus.CONFLICT,"El número de espacio ya existe en esta calle.",exception);
        return exception;
    }
}
