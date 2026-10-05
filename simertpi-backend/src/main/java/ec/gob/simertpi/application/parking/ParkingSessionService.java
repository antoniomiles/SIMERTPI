package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.ParkingSessionStatus;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.entity.Street;
import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.parking.repository.StreetRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Set;

@Service
@Transactional
public class ParkingSessionService {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ec.gob.simertpi.application.operations.OperationalMetrics metrics;


    private static final Set<String> STAFF_ROLES = Set.of(
            "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN", "IT_ADMIN", "AUDITOR"
    );
    private static final Set<String> OPERATOR_ROLES = Set.of(
            "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN"
    );

    private final ParkingSessionRepository parkingSessionRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final ParkingSpaceRepository parkingSpaceRepository;
    private final StreetRepository streetRepository;
    private final ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;
    private final ZoneRepository zoneRepository;
    private final ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository extensions;

    public ParkingSessionService(
            ParkingSessionRepository parkingSessionRepository,
            UserRepository userRepository,
            VehicleRepository vehicleRepository,
            ParkingSpaceRepository parkingSpaceRepository,
            StreetRepository streetRepository,
            ec.gob.simertpi.application.parking.rules.ParkingRulesService rules,
            ZoneRepository zoneRepository,
            ec.gob.simertpi.domain.parking.extension.repository.SessionExtensionRepository extensions
    ) {
        this.parkingSessionRepository = parkingSessionRepository;
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.parkingSpaceRepository = parkingSpaceRepository;
        this.streetRepository = streetRepository;
        this.rules = rules;
        this.zoneRepository = zoneRepository;
        this.extensions = extensions;
    }

    @Transactional(readOnly = true)
    public List<ParkingSession> findAll() {
        return parkingSessionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<ParkingSession> findAllForUsername(String username) {
        User user = findAuthenticatedUser(username);
        if (user.getRoles().stream().anyMatch(role -> STAFF_ROLES.contains(role.getCode()))) {
            return parkingSessionRepository.findAll();
        }
        return parkingSessionRepository.findByUserId(user.getId());
    }

    @Transactional(readOnly = true)
    public ParkingSession findById(UUID id) {
        return parkingSessionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));
    }

    @Transactional(readOnly = true)
    public ParkingSession findByIdForUsername(UUID id, String username) {
        ParkingSession session = findById(id);
        requireOwnerOrStaff(session, findAuthenticatedUser(username));
        return session;
    }

    @Transactional(readOnly = true)
    public List<ParkingSession> findByUserId(UUID userId) {
        return parkingSessionRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<ParkingSession> findByUserIdForUsername(UUID requestedUserId, String username) {
        User user = findAuthenticatedUser(username);
        if (!user.getId().equals(requestedUserId) && !hasStaffRole(user)) {
            throw new ForbiddenException();
        }
        return parkingSessionRepository.findByUserId(requestedUserId);
    }

    @Transactional(readOnly = true)
    public List<ParkingSession> findByVehicleId(UUID vehicleId) {
        return parkingSessionRepository.findByVehicleId(vehicleId);
    }

    @Transactional(readOnly = true)
    public List<ParkingSession> findByVehicleIdForUsername(UUID vehicleId, String username) {
        User user = findAuthenticatedUser(username);
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
        if (!vehicle.getUserId().equals(user.getId()) && !hasStaffRole(user)) {
            throw new ForbiddenException();
        }
        return parkingSessionRepository.findByVehicleId(vehicleId);
    }

    @Transactional
    public ParkingSession close(UUID sessionId) {
        ParkingSession session = parkingSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));

        if ("COMPLETED".equals(session.getStatus())) return session;
        if (extensions.findPendingByParkingSessionId(sessionId).isPresent()) {
            throw new IllegalArgumentException("Resolve the pending extension payment before closing this session");
        }
        if (!"ACTIVE".equals(session.getStatus())
                && !"EXTENDED".equals(session.getStatus())
                && !"EXPIRED".equals(session.getStatus())
                && !"MAX_TIME_REACHED".equals(session.getStatus())) {
            throw new IllegalArgumentException(
                    "Parking session is not active"
            );
        }

        OffsetDateTime now = OffsetDateTime.now();

        session.setEndedAt(now);
        session.setStatus("COMPLETED");
        session.setUpdatedAt(now);

        return parkingSessionRepository.save(session);
    }

    @Transactional
    @Audited(action = "PARKING_SESSION_COMPLETED", resourceType = "PARKING_SESSION", resourceIdArgument = 0)
    public ParkingSession closeForUsername(UUID sessionId, String username) {
        ParkingSession session = parkingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));
        requireOwnerOrOperator(session, findAuthenticatedUser(username));
        return close(sessionId);
    }

    @Transactional(readOnly = true)
    public List<ParkingSession> findByStatusIn(List<String> statuses) {
        return parkingSessionRepository.findByStatusIn(statuses);
    }

    @Transactional
    public ParkingSession create(
            UUID userId,
            UUID vehicleId,
            UUID parkingSpaceId,
            UUID tariffId,
            Integer durationMinutes
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.isEnabled()) {
            throw new IllegalArgumentException("User is disabled");
        }

        Vehicle vehicle = vehicleRepository.findByIdForUpdate(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));

        if (!vehicle.getUserId().equals(user.getId())) {
            throw new ForbiddenException();
        }

        if (!vehicle.isActive()) {
            throw new IllegalArgumentException("Vehicle is inactive");
        }

        ParkingSpace parkingSpace = parkingSpaceRepository.findById(parkingSpaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking space not found"));

        if (!parkingSpace.isActive()) {
            throw new IllegalArgumentException("Parking space is inactive");
        }

        if (parkingSessionRepository.existsByParkingSpaceIdAndStatusIn(
                parkingSpaceId,
                ParkingSessionStatus.occupyingCodes()
        )) {
            throw new IllegalArgumentException("Parking space already has an active session");
        }

        Street street = streetRepository.findById(parkingSpace.getStreetId())
                .orElseThrow(() -> new ResourceNotFoundException("Street not found"));

        if (!street.isActive()) {
            throw new IllegalArgumentException("Parking street is inactive");
        }

        Zone zone = zoneRepository.findById(street.getZoneId())
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found"));
        if (!zone.isActive()) {
            throw new IllegalArgumentException("Parking zone is inactive");
        }

        if (durationMinutes == null) throw new IllegalArgumentException("INVALID_DURATION");
        OffsetDateTime now = OffsetDateTime.now();
        var quote = rules.evaluateForZone(zone.getId(), parkingSpaceId, now.toInstant(), durationMinutes);
        if (!quote.operational()) throw new IllegalArgumentException(quote.reasonCode());
        if (!java.util.Objects.equals(tariffId, quote.tariffId())) throw new IllegalArgumentException("TARIFF_NOT_APPLICABLE");
        OffsetDateTime expectedEndAt = quote.expiresAt().atOffset(now.getOffset());

        ParkingSession session = new ParkingSession();
        session.setId(UUID.randomUUID());
        session.setUserId(user.getId());
        session.setVehicleId(vehicle.getId());
        session.setParkingSpaceId(parkingSpace.getId());
        session.setTariffId(quote.tariffId());
        session.setStartedAt(now);
        session.setExpectedEndAt(expectedEndAt);
        session.setEndedAt(null);
        session.setStatus("PENDING_PAYMENT");
        session.setTotalAmount(BigDecimal.ZERO);
        session.setExtensionCount(0);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);

        if(metrics!=null)metrics.event(ec.gob.simertpi.application.operations.OperationalMetrics.Event.SESSION_CREATED);
        return parkingSessionRepository.save(session);
    }

    @Transactional
    public ParkingSession createFromQr(UUID userId, String qrCode, UUID vehicleId,
                                       UUID tariffId, Integer durationMinutes) {
        ParkingSpace space = parkingSpaceRepository.findByQrCode(qrCode)
                .orElseThrow(() -> new ResourceNotFoundException("Parking space QR code not found"));
        return create(userId, vehicleId, space.getId(), tariffId, durationMinutes);
    }

    @Transactional
    public ParkingSession createFromUsername(String username, String qrCode, UUID vehicleId,
                                            UUID tariffId, Integer durationMinutes) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        return createFromQr(user.getId(), qrCode, vehicleId, tariffId, durationMinutes);
    }

    @Transactional
    @Audited(action = "PARKING_SESSION_CREATED", resourceType = "PARKING_SESSION")
    public ParkingSession createForUsername(String username, UUID vehicleId, UUID parkingSpaceId,
                                            UUID tariffId, Integer durationMinutes) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        return create(user.getId(), vehicleId, parkingSpaceId, tariffId, durationMinutes);
    }

    @Transactional(readOnly = true)
    public void assertSessionOwner(UUID sessionId, String username) {
        ParkingSession session = parkingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));
        if (!session.getUserId().equals(findAuthenticatedUser(username).getId())) {
            throw new ForbiddenException();
        }
    }

    private User findAuthenticatedUser(String username) {
        return userRepository.findByUsernameWithRoles(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
    }

    private boolean hasStaffRole(User user) {
        return user.getRoles().stream().anyMatch(role -> STAFF_ROLES.contains(role.getCode()));
    }

    private void requireOwnerOrStaff(ParkingSession session, User user) {
        if (!session.getUserId().equals(user.getId()) && !hasStaffRole(user)) {
            throw new ForbiddenException();
        }
    }

    private void requireOwnerOrOperator(ParkingSession session, User user) {
        boolean operator = user.getRoles().stream()
                .anyMatch(role -> OPERATOR_ROLES.contains(role.getCode()));
        if (!session.getUserId().equals(user.getId()) && !operator) {
            throw new ForbiddenException();
        }
    }
}
