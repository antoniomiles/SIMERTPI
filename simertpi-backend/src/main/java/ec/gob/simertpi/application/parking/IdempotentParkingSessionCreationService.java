package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.IdempotencyConflictException;
import ec.gob.simertpi.api.InvalidIdempotencyKeyException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.application.idempotency.IdempotencyRecord;
import ec.gob.simertpi.application.idempotency.ParkingSessionIdempotencyStore;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class IdempotentParkingSessionCreationService {

    private static final String OPERATION = "CREATE_PARKING_SESSION";
    private static final int RESPONSE_CREATED = 201;
    private static final int KEY_TTL_HOURS = 24;

    private final ParkingSessionIdempotencyStore idempotencyStore;
    private final UserRepository userRepository;
    private final ParkingSessionService parkingSessionService;
    private final ParkingSessionRepository parkingSessionRepository;

    public IdempotentParkingSessionCreationService(ParkingSessionIdempotencyStore idempotencyStore,
                                                   UserRepository userRepository,
                                                   ParkingSessionService parkingSessionService,
                                                   ParkingSessionRepository parkingSessionRepository) {
        this.idempotencyStore = idempotencyStore;
        this.userRepository = userRepository;
        this.parkingSessionService = parkingSessionService;
        this.parkingSessionRepository = parkingSessionRepository;
    }

    @Transactional
    @Audited(action = "PARKING_SESSION_CREATED", resourceType = "PARKING_SESSION", idempotencyArgument = 1)
    public ParkingSession create(String username, String suppliedKey, String qrCode,
                                 UUID vehicleId, UUID tariffId, Integer durationMinutes) {
        if (suppliedKey == null || suppliedKey.isBlank() || suppliedKey.length() > 255) {
            throw new InvalidIdempotencyKeyException();
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        String key = user.getId() + ":" + OPERATION + ":" + sha256(suppliedKey.trim());
        String canonicalRequest = qrCode.length() + ":" + qrCode + "|" + vehicleId
                + "|" + tariffId + "|" + durationMinutes;
        String requestHash = sha256(canonicalRequest);
        OffsetDateTime now = OffsetDateTime.now();

        if (!idempotencyStore.claim(key, OPERATION, user.getId(), requestHash,
                now, now.plusHours(KEY_TTL_HOURS))) {
            IdempotencyRecord existing = idempotencyStore.find(key);
            if (!user.getId().equals(existing.userId())
                    || !OPERATION.equals(existing.operationType())
                    || !requestHash.equals(existing.requestHash())
                    || !"COMPLETED".equals(existing.status())
                    || !Integer.valueOf(RESPONSE_CREATED).equals(existing.responseStatus())) {
                throw new IdempotencyConflictException();
            }
            try {
                return parkingSessionRepository.findById(UUID.fromString(existing.responseBody()))
                        .orElseThrow(IdempotencyConflictException::new);
            } catch (IllegalArgumentException malformedStoredResult) {
                throw new IdempotencyConflictException();
            }
        }

        ParkingSession session = parkingSessionService.createFromUsername(username, qrCode,
                vehicleId, tariffId, durationMinutes);
        parkingSessionRepository.flush();
        idempotencyStore.complete(key, RESPONSE_CREATED, session.getId().toString(), OffsetDateTime.now());
        return session;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
