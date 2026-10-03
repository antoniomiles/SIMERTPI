package ec.gob.simertpi.application.permits;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.IdempotencyConflictException;
import ec.gob.simertpi.api.InvalidIdempotencyKeyException;
import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.permits.dto.CreatePermitRequest;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import ec.gob.simertpi.domain.permits.entity.Permit;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.infrastructure.permits.JpaPermitRepository;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import ec.gob.simertpi.application.idempotency.IdempotencyRecord;
import ec.gob.simertpi.application.idempotency.IdempotencyStore;
import ec.gob.simertpi.application.audit.Audited;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PermitService {
    private static final String OPERATION = "CREATE_PERMIT";
    private static final int IDEMPOTENCY_TTL_HOURS = 24;
    private static final Set<String> MUNICIPAL = Set.of(
            "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN");

    private final JpaPermitRepository permits;
    private final UserRepository users;
    private final VehicleRepository vehicles;
    private final ZoneRepository zones;
    private final IdempotencyStore idempotency;
    private final PermitEventPublisher events;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    public PermitService(JpaPermitRepository permits, UserRepository users, VehicleRepository vehicles,
                         ZoneRepository zones, IdempotencyStore idempotency, PermitEventPublisher events, ObjectMapper mapper,
                         EntityManager entityManager) {
        this.permits = permits;
        this.users = users;
        this.vehicles = vehicles;
        this.zones = zones;
        this.idempotency = idempotency;
        this.events = events;
        this.mapper = mapper;
        this.entityManager = entityManager;
    }

    @Transactional
    @Audited(action = "PERMIT_CREATED", resourceType = "PERMIT", idempotencyArgument = 2)
    public Permit create(String username, Set<String> authorities, String suppliedKey, CreatePermitRequest request) {
        requireAdmin(authorities);
        User administrator = authenticatedUser(username);
        String key = null;
        if (suppliedKey != null) {
            if (suppliedKey.isBlank() || suppliedKey.length() > 255) throw new InvalidIdempotencyKeyException();
            key = administrator.getId() + ":" + OPERATION + ":" + sha256(suppliedKey.trim());
            String requestHash = hashPayload(request);
            OffsetDateTime now = OffsetDateTime.now();
            if (!idempotency.claim(key, OPERATION, administrator.getId(), requestHash,
                    now, now.plusHours(IDEMPOTENCY_TTL_HOURS))) {
                IdempotencyRecord previous = idempotency.find(key);
                if (!administrator.getId().equals(previous.userId())
                        || !OPERATION.equals(previous.operationType())
                        || !requestHash.equals(previous.requestHash())
                        || !"COMPLETED".equals(previous.status()) || previous.responseBody() == null) {
                    throw new IdempotencyConflictException();
                }
                try {
                    return permits.findById(UUID.fromString(previous.responseBody()))
                            .orElseThrow(IdempotencyConflictException::new);
                } catch (IllegalArgumentException invalidStoredId) {
                    throw new IdempotencyConflictException();
                }
            }
        }

        if (request.validFrom() == null || request.validTo() == null
                || !request.validTo().isAfter(request.validFrom())) {
            throw new InvalidRequestException("La fecha de expiración debe ser posterior al inicio.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!request.validTo().isAfter(now)) throw new InvalidRequestException("No se puede crear un permiso vencido.");
        User beneficiary = users.findById(request.userId())
                .filter(User::isEnabled).orElseThrow(() -> new ResourceNotFoundException("Beneficiario no encontrado"));

        // Serialize overlapping permit issuance for a vehicle without a schema change.
        Vehicle vehicle = entityManager.find(Vehicle.class, request.vehicleId(), LockModeType.PESSIMISTIC_WRITE);
        if (vehicle == null || !vehicle.isActive()) throw new ResourceNotFoundException("Vehículo no encontrado");
        if (!vehicle.getUserId().equals(beneficiary.getId())) {
            throw new InvalidRequestException("El vehículo no pertenece al beneficiario indicado.");
        }
        if (request.zoneId() != null) {
            Zone zone = zones.findById(request.zoneId()).orElseThrow(() -> new ResourceNotFoundException("Zona no encontrada"));
            if (!zone.isActive()) throw new InvalidRequestException("La zona no está activa.");
        }
        String type = request.permitType().trim();
        String code = request.authorizationCode().trim();
        String notes = request.notes() == null ? null : request.notes().trim();
        if (type.isEmpty() || type.length() > 50 || code.isEmpty() || code.length() > 100
                || (notes != null && notes.length() > 500)) {
            throw new InvalidRequestException("Los datos del permiso exceden los límites permitidos.");
        }
        if (permits.existsOverlapping(vehicle.getId(), request.zoneId(), request.validFrom(), request.validTo())) {
            throw new InvalidRequestException("Ya existe un permiso vigente o programado que se solapa.");
        }
        Permit permit = new Permit();
        permit.setId(UUID.randomUUID());
        permit.setUserId(beneficiary.getId());
        permit.setVehicleId(vehicle.getId());
        permit.setZoneId(request.zoneId());
        permit.setPermitType(type);
        permit.setStatus(request.validFrom().isAfter(now) ? "PENDING" : "ACTIVE");
        permit.setValidFrom(request.validFrom());
        permit.setValidTo(request.validTo());
        permit.setAuthorizationCode(code);
        permit.setNotes(notes);
        permit.setCreatedAt(now);
        permit.setUpdatedAt(now);
        Permit saved = permits.saveAndFlush(permit);
        events.publish(saved, "PERMIT_CREATED", administrator.getId());
        if (key != null) idempotency.complete(key, 201, saved.getId().toString(), OffsetDateTime.now());
        return saved;
    }

    @Transactional
    public List<Permit> own(String username) {
        User user = authenticatedUser(username);
        OffsetDateTime now = OffsetDateTime.now();
        return permits.findByUserIdOrderByValidFromDesc(user.getId()).stream()
                .peek(permit -> updateLifecycle(permit, now)).toList();
    }

    @Transactional
    public Permit byId(String username, UUID id, Set<String> authorities) {
        User caller = authenticatedUser(username);
        Permit permit = permits.findById(id).orElseThrow(() -> new ResourceNotFoundException("Permiso no encontrado"));
        if (!caller.getId().equals(permit.getUserId()) && authorities.stream().noneMatch(MUNICIPAL::contains)) {
            throw new ForbiddenException();
        }
        updateLifecycle(permit, OffsetDateTime.now());
        return permit;
    }

    @Transactional
    public List<Permit> byPlate(String plate, Set<String> authorities) {
        requireMunicipal(authorities);
        Vehicle vehicle = vehicles.findByPlate(plate.trim().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Vehículo no encontrado"));
        OffsetDateTime now = OffsetDateTime.now();
        return permits.findByVehicleIdOrderByValidFromDesc(vehicle.getId()).stream()
                .peek(permit -> updateLifecycle(permit, now)).toList();
    }

    @Transactional
    public List<Permit> active(Set<String> authorities) {
        requireMunicipal(authorities);
        OffsetDateTime now = OffsetDateTime.now();
        expireDue(now);
        activateDue(now);
        return permits.findActive(now);
    }

    @Transactional
    public List<Permit> expired(Set<String> authorities) {
        requireMunicipal(authorities);
        OffsetDateTime now = OffsetDateTime.now();
        expireDue(now);
        return permits.findExpired(now);
    }

    @Transactional
    @Audited(action = "PERMIT_CANCELLED", resourceType = "PERMIT", resourceIdArgument = 1)
    public Permit cancel(String username, UUID id, Set<String> authorities) {
        requireAdmin(authorities);
        User actor = authenticatedUser(username);
        Permit permit = permits.findById(id).orElseThrow(() -> new ResourceNotFoundException("Permiso no encontrado"));
        updateLifecycle(permit, OffsetDateTime.now());
        if (!"ACTIVE".equals(permit.getStatus()) && !"PENDING".equals(permit.getStatus())) {
            throw new InvalidRequestException("Solo se pueden cancelar permisos pendientes o activos.");
        }
        permit.setStatus("CANCELLED");
        permit.setUpdatedAt(OffsetDateTime.now());
        Permit saved = permits.save(permit);
        events.publish(saved, "PERMIT_CANCELLED", actor.getId());
        return saved;
    }

    @Transactional
    @Audited(action = "PERMIT_EXPIRED", resourceType = "PERMIT")
    public int expireDue(OffsetDateTime now) {
        List<Permit> due = permits.findExpiredActive(now);
        due.forEach(permit -> {
            permit.setStatus("EXPIRED");
            permit.setUpdatedAt(now);
            events.publish(permit, "PERMIT_EXPIRED", null);
        });
        return due.size();
    }

    @Transactional
    public int activateDue(OffsetDateTime now) {
        List<Permit> due = permits.findPendingToActivate(now);
        due.forEach(permit -> { permit.setStatus("ACTIVE"); permit.setUpdatedAt(now); });
        return due.size();
    }

    private void updateLifecycle(Permit permit, OffsetDateTime now) {
        if (("ACTIVE".equals(permit.getStatus()) || "PENDING".equals(permit.getStatus()))
                && !permit.getValidTo().isAfter(now)) {
            permit.setStatus("EXPIRED");
            permit.setUpdatedAt(now);
            events.publish(permit, "PERMIT_EXPIRED", null);
        } else if ("PENDING".equals(permit.getStatus()) && !permit.getValidFrom().isAfter(now)) {
            permit.setStatus("ACTIVE"); permit.setUpdatedAt(now);
        }
    }

    private User authenticatedUser(String username) {
        return users.findByUsername(username).filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));
    }
    private void requireAdmin(Set<String> authorities) {
        if (!authorities.contains("SIMERTPI_ADMIN")) throw new ForbiddenException();
    }
    private void requireMunicipal(Set<String> authorities) {
        if (authorities.stream().noneMatch(MUNICIPAL::contains)) throw new ForbiddenException();
    }
    private String hashPayload(Object payload) {
        try { return sha256(mapper.writeValueAsString(payload)); }
        catch (JsonProcessingException e) { throw new IllegalStateException("No se pudo validar la solicitud idempotente", e); }
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 no disponible", e); }
    }
}
