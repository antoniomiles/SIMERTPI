package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.parking.AdminCatalogUpdateRequests.SpaceUpdate;
import ec.gob.simertpi.api.parking.AdminCatalogUpdateRequests.StreetUpdate;
import ec.gob.simertpi.api.parking.AdminCatalogUpdateRequests.ZoneUpdate;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.entity.Street;
import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.parking.repository.StreetRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Administrative catalog edits. Parent relationships stay immutable to preserve historical meaning. */
@Service
public class AdminParkingCatalogService {
    private static final List<String> OCCUPYING = List.of(
            "PENDING_PAYMENT", "ACTIVE", "EXTENDED", "EXPIRED", "MAX_TIME_REACHED");
    private final ZoneRepository zones;
    private final StreetRepository streets;
    private final ParkingSpaceRepository spaces;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AdminParkingCatalogService(ZoneRepository zones, StreetRepository streets,
            ParkingSpaceRepository spaces, JdbcTemplate jdbc, AuditService audit) {
        this.zones = zones; this.streets = streets; this.spaces = spaces; this.jdbc = jdbc; this.audit = audit;
    }

    @Transactional
    public Zone updateZone(UUID id, ZoneUpdate request) {
        Zone zone = zones.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("La zona no existe"));
        checkVersion(zone.getUpdatedAt(), request.expectedUpdatedAt());
        if (!zone.getCode().equals(request.code()) && zones.existsByCode(request.code())) conflict("El código de zona ya existe.");
        if (zone.isActive() && !request.active() && openSessionsInZone(id) > 0)
            conflict("No se puede deshabilitar la zona porque tiene sesiones operativas abiertas en sus espacios.");
        Map<String, Object> before = Map.of("code", zone.getCode(), "name", zone.getName(), "active", zone.isActive());
        zone.setCode(request.code().trim()); zone.setName(request.name().trim());
        zone.setDescription(blankToNull(request.description())); zone.setActive(request.active());
        zone.setUpdatedAt(nextTime(zone.getUpdatedAt()));
        Zone saved = zones.saveAndFlush(zone);
        audit.success("CONFIGURATION_ZONE_UPDATED", "PARKING_ZONE", saved.getId(), null,
                Map.of("before", compact(before), "after", compact(Map.of("code", saved.getCode(), "name", saved.getName(), "active", saved.isActive()))));
        return saved;
    }

    @Transactional
    public Street updateStreet(UUID id, StreetUpdate request) {
        Street street = streets.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("La calle no existe"));
        checkVersion(street.getUpdatedAt(), request.expectedUpdatedAt());
        if (!street.getCode().equals(request.code()) && streets.existsByZoneIdAndCodeAndIdNot(street.getZoneId(), request.code(), id))
            conflict("El código de calle ya existe en esta zona.");
        if (street.isActive() && !request.active() && openSessionsInStreet(id) > 0)
            conflict("No se puede deshabilitar la calle porque tiene sesiones operativas abiertas en sus espacios.");
        Map<String, Object> before = Map.of("code", street.getCode(), "name", street.getName(), "active", street.isActive());
        street.setCode(request.code().trim()); street.setName(request.name().trim());
        street.setDescription(blankToNull(request.description())); street.setActive(request.active());
        street.setUpdatedAt(nextTime(street.getUpdatedAt()));
        Street saved = streets.saveAndFlush(street);
        audit.success("CONFIGURATION_STREET_UPDATED", "PARKING_STREET", saved.getId(), null,
                Map.of("before", compact(before), "after", compact(Map.of("code", saved.getCode(), "name", saved.getName(), "active", saved.isActive()))));
        return saved;
    }

    @Transactional
    public ParkingSpace updateSpace(UUID id, SpaceUpdate request) {
        ParkingSpace space = spaces.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("El espacio no existe"));
        checkVersion(space.getUpdatedAt(), request.expectedUpdatedAt());
        if (!space.getCode().equals(request.code()) && spaces.existsByCodeAndIdNot(request.code(), id)) conflict("El código del espacio ya existe.");
        if (!space.getQrCode().equals(request.qrCode()) && spaces.existsByQrCodeAndIdNot(request.qrCode(), id)) conflict("El código QR ya existe.");
        if (!space.getSpaceNumber().equals(request.spaceNumber()) && spaces.existsByStreetIdAndSpaceNumberAndIdNot(space.getStreetId(), request.spaceNumber(), id)) conflict("El número ya existe en esta calle.");
        if (space.isActive() && !request.active() && openSessionsInSpace(id) > 0)
            conflict("No se puede deshabilitar el espacio mientras tenga una sesión operativa abierta.");
        Map<String, Object> before = Map.of("code", space.getCode(), "spaceNumber", space.getSpaceNumber(), "active", space.isActive());
        space.setCode(request.code().trim()); space.setQrCode(request.qrCode().trim());
        space.setSpaceNumber(request.spaceNumber().trim()); space.setLatitude(request.latitude()); space.setLongitude(request.longitude());
        space.setSpaceType(blankToDefault(request.spaceType(), "STANDARD")); space.setActive(request.active());
        space.setUpdatedAt(nextTime(space.getUpdatedAt()));
        ParkingSpace saved = spaces.saveAndFlush(space);
        audit.success("CONFIGURATION_PARKING_SPACE_UPDATED", "PARKING_SPACE", saved.getId(), null,
                Map.of("before", compact(before), "after", compact(Map.of("code", saved.getCode(), "spaceNumber", saved.getSpaceNumber(), "active", saved.isActive()))));
        return saved;
    }

    private long openSessionsInZone(UUID id) { return count("z.id=?", id); }
    private long openSessionsInStreet(UUID id) { return count("st.id=?", id); }
    private long openSessionsInSpace(UUID id) { return count("p.id=?", id); }
    private long count(String predicate, UUID id) {
        String sql = "SELECT count(*) FROM parking.parking_sessions ps JOIN parking.parking_spaces p ON p.id=ps.parking_space_id "
                + "JOIN parking.streets st ON st.id=p.street_id JOIN parking.zones z ON z.id=st.zone_id WHERE " + predicate
                + " AND ps.status IN ('PENDING_PAYMENT','ACTIVE','EXTENDED','EXPIRED','MAX_TIME_REACHED')";
        return jdbc.queryForObject(sql, Long.class, id);
    }
    private void checkVersion(OffsetDateTime actual, OffsetDateTime expected) {
        if (actual == null || !actual.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .equals(expected.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Otro administrador modificó este registro. Actualice la información.");
    }
    private OffsetDateTime nextTime(OffsetDateTime previous) {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return previous != null && !now.isAfter(previous) ? previous.plusNanos(1_000) : now;
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String blankToDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private String compact(Map<String, Object> values) { return values.toString(); }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
