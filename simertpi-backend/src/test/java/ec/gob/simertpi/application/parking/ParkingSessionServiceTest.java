package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.application.parking.calendar.ParkingCalendarService;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.entity.Street;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.parking.repository.StreetRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
class ParkingSessionServiceTest {

    @Mock
    private ParkingSessionRepository parkingSessionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private ParkingSpaceRepository parkingSpaceRepository;

    @Mock
    private StreetRepository streetRepository;

    @Mock
    private TariffRepository tariffRepository;

    @Mock
    private ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;

    @Mock
    private ZoneRepository zoneRepository;

    @InjectMocks
    private ParkingSessionService parkingSessionService;

    private UUID userId;
    private UUID vehicleId;
    private UUID parkingSpaceId;
    private UUID streetId;
    private UUID zoneId;
    private UUID tariffId;

    private User user;
    private Vehicle vehicle;
    private ParkingSpace parkingSpace;
    private Street street;
    private Tariff tariff;
    private Zone zone;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        vehicleId = UUID.randomUUID();
        parkingSpaceId = UUID.randomUUID();
        streetId = UUID.randomUUID();
        zoneId = UUID.randomUUID();
        tariffId = UUID.randomUUID();

        OffsetDateTime now = OffsetDateTime.now();

        user = new User();
        user.setId(userId);

        vehicle = new Vehicle();
        vehicle.setId(vehicleId);
        vehicle.setUserId(userId);
        vehicle.setPlate("ABC1234");
        vehicle.setActive(true);

        street = new Street();
        street.setId(streetId);
        street.setZoneId(zoneId);
        street.setCode("ST-001");
        street.setName("Calle Principal");
        street.setActive(true);

        zone = new Zone();
        zone.setId(zoneId);
        zone.setActive(true);

        parkingSpace = new ParkingSpace();
        parkingSpace.setId(parkingSpaceId);
        parkingSpace.setStreetId(streetId);
        parkingSpace.setCode("ESP-001");
        parkingSpace.setQrCode("QR-ESP-001");
        parkingSpace.setSpaceNumber("001");
        parkingSpace.setActive(true);

        tariff = new Tariff();
        tariff.setId(tariffId);
        tariff.setCode("TAR-001");
        tariff.setName("Tarifa estÃƒÂ¡ndar");
        tariff.setAmount(new BigDecimal("0.25"));
        tariff.setDurationMinutes(60);
        tariff.setMinMinutes(30);
        tariff.setMaxContinuousMinutes(240);
        tariff.setActive(true);
        tariff.setValidFrom(now.minusDays(1));
        tariff.setValidTo(now.plusDays(30));
    }

    @Test
    void shouldCreateSessionWhenParkingCalendarIsOperational() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findByIdForUpdate(vehicleId)).thenReturn(Optional.of(vehicle));
        when(parkingSpaceRepository.findById(parkingSpaceId)).thenReturn(Optional.of(parkingSpace));
        when(parkingSessionRepository.existsByParkingSpaceIdAndStatusIn(parkingSpaceId, List.of("PENDING_PAYMENT", "ACTIVE", "EXTENDED", "EXPIRED", "MAX_TIME_REACHED"))).thenReturn(false);
        when(streetRepository.findById(streetId)).thenReturn(Optional.of(street));
        when(zoneRepository.findById(zoneId)).thenReturn(Optional.of(zone));
        var quote = mockQuote(true, "RULES_RESOLVED");
        when(rules.evaluateForZone(any(), any(), any(), any())).thenReturn(quote);

        when(parkingSessionRepository.save(any(ParkingSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ParkingSession savedSession = parkingSessionService.create(
                userId,
                vehicleId,
                parkingSpaceId,
                tariffId,
                60
        );

        assertEquals(userId, savedSession.getUserId());
        assertEquals(vehicleId, savedSession.getVehicleId());
        assertEquals(parkingSpaceId, savedSession.getParkingSpaceId());
        assertEquals(tariffId, savedSession.getTariffId());
        assertEquals("PENDING_PAYMENT", savedSession.getStatus());

        verify(parkingSessionRepository).save(any(ParkingSession.class));
        verify(rules).evaluateForZone(any(), any(), any(), any());
    }

    @Test
    void shouldRejectSessionWhenParkingCalendarIsNotOperational() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findByIdForUpdate(vehicleId)).thenReturn(Optional.of(vehicle));
        when(parkingSpaceRepository.findById(parkingSpaceId)).thenReturn(Optional.of(parkingSpace));
        when(parkingSessionRepository.existsByParkingSpaceIdAndStatusIn(parkingSpaceId, List.of("PENDING_PAYMENT", "ACTIVE", "EXTENDED", "EXPIRED", "MAX_TIME_REACHED"))).thenReturn(false);
        when(streetRepository.findById(streetId)).thenReturn(Optional.of(street));
        when(zoneRepository.findById(zoneId)).thenReturn(Optional.of(zone));
        when(rules.evaluateForZone(any(), any(), any(), any())).thenReturn(mockQuote(false, "OUTSIDE_OPERATION_HOURS"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parkingSessionService.create(
                        userId,
                        vehicleId,
                        parkingSpaceId,
                        tariffId,
                        60
                )
        );

        assertEquals(
                "OUTSIDE_OPERATION_HOURS",
                exception.getMessage()
        );

        verify(rules).evaluateForZone(any(), any(), any(), any());
        verify(parkingSessionRepository, never()).save(any(ParkingSession.class));
    }

    @Test
    void shouldRejectDisabledUser() {
        user.setEnabled(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThrows(IllegalArgumentException.class,
                () -> parkingSessionService.create(userId, vehicleId, parkingSpaceId, tariffId, 60));
        verifyNoInteractions(vehicleRepository);
    }

    @Test
    void shouldRejectVehicleOwnedByAnotherUser() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        vehicle.setUserId(UUID.randomUUID());
        when(vehicleRepository.findByIdForUpdate(vehicleId)).thenReturn(Optional.of(vehicle));
        assertThrows(ec.gob.simertpi.api.ForbiddenException.class,
                () -> parkingSessionService.create(userId, vehicleId, parkingSpaceId, tariffId, 60));
        verify(parkingSessionRepository, never()).save(any(ParkingSession.class));
    }

    @Test
    void shouldRejectInactiveVehicle() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        vehicle.setActive(false);
        when(vehicleRepository.findByIdForUpdate(vehicleId)).thenReturn(Optional.of(vehicle));
        assertThrows(IllegalArgumentException.class,
                () -> parkingSessionService.create(userId, vehicleId, parkingSpaceId, tariffId, 60));
        verify(parkingSessionRepository, never()).save(any(ParkingSession.class));
    }

    @Test
    void shouldRejectUnknownQrCode() {
        when(parkingSpaceRepository.findByQrCode("missing-qr")).thenReturn(Optional.empty());
        assertThrows(ec.gob.simertpi.api.ResourceNotFoundException.class,
                () -> parkingSessionService.createFromQr(userId, "missing-qr", vehicleId, tariffId, 60));
        verify(parkingSessionRepository, never()).save(any(ParkingSession.class));
    }

    @Test
    void shouldRejectOccupiedParkingSpace() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findByIdForUpdate(vehicleId)).thenReturn(Optional.of(vehicle));
        when(parkingSpaceRepository.findById(parkingSpaceId)).thenReturn(Optional.of(parkingSpace));
        when(parkingSessionRepository.existsByParkingSpaceIdAndStatusIn(parkingSpaceId,
                List.of("PENDING_PAYMENT", "ACTIVE", "EXTENDED", "EXPIRED", "MAX_TIME_REACHED"))).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> parkingSessionService.create(userId, vehicleId, parkingSpaceId, tariffId, 60));
        verify(parkingSessionRepository, never()).save(any(ParkingSession.class));
    }

    @Test
    void shouldRejectTariffOutsideValidity() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findByIdForUpdate(vehicleId)).thenReturn(Optional.of(vehicle));
        when(parkingSpaceRepository.findById(parkingSpaceId)).thenReturn(Optional.of(parkingSpace));
        when(parkingSessionRepository.existsByParkingSpaceIdAndStatusIn(parkingSpaceId,
                List.of("PENDING_PAYMENT", "ACTIVE", "EXTENDED", "EXPIRED", "MAX_TIME_REACHED"))).thenReturn(false);
        when(streetRepository.findById(streetId)).thenReturn(Optional.of(street));
        when(zoneRepository.findById(zoneId)).thenReturn(Optional.of(zone));
        tariff.setValidTo(OffsetDateTime.now().minusDays(1));
        when(rules.evaluateForZone(any(), any(), any(), any())).thenReturn(mockQuote(false, "NO_ACTIVE_TARIFF"));
        assertThrows(IllegalArgumentException.class,
                () -> parkingSessionService.create(userId, vehicleId, parkingSpaceId, tariffId, 60));
        verify(parkingSessionRepository, never()).save(any(ParkingSession.class));
    }

    @Test
    void authenticatedUserCanReadOwnSession() {
        user.setUsername("citizen-a");
        ParkingSession session = new ParkingSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        when(userRepository.findByUsernameWithRoles("citizen-a")).thenReturn(Optional.of(user));
        when(parkingSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertEquals(session, parkingSessionService.findByIdForUsername(session.getId(), "citizen-a"));
    }

    @Test
    void authenticatedUserCannotReadAnotherUsersSession() {
        user.setUsername("citizen-a");
        ParkingSession session = new ParkingSession();
        session.setId(UUID.randomUUID());
        session.setUserId(UUID.randomUUID());
        when(userRepository.findByUsernameWithRoles("citizen-a")).thenReturn(Optional.of(user));
        when(parkingSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThrows(ec.gob.simertpi.api.ForbiddenException.class,
                () -> parkingSessionService.findByIdForUsername(session.getId(), "citizen-a"));
    }

    @Test
    void authenticatedUserCanReadOwnVehicleSessions() {
        user.setUsername("citizen-a");
        when(userRepository.findByUsernameWithRoles("citizen-a")).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(parkingSessionRepository.findByVehicleId(vehicleId)).thenReturn(List.of());

        assertDoesNotThrow(() -> parkingSessionService.findByVehicleIdForUsername(vehicleId, "citizen-a"));
        verify(parkingSessionRepository).findByVehicleId(vehicleId);
    }

    @Test
    void authenticatedUserCannotReadAnotherUsersVehicleSessions() {
        user.setUsername("citizen-a");
        vehicle.setUserId(UUID.randomUUID());
        when(userRepository.findByUsernameWithRoles("citizen-a")).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));

        assertThrows(ec.gob.simertpi.api.ForbiddenException.class,
                () -> parkingSessionService.findByVehicleIdForUsername(vehicleId, "citizen-a"));
        verify(parkingSessionRepository, never()).findByVehicleId(vehicleId);
    }
    private ec.gob.simertpi.application.parking.rules.ParkingRulesResult mockQuote(boolean operational, String reason) {
        return new ec.gob.simertpi.application.parking.rules.ParkingRulesResult(zoneId, parkingSpaceId,
                java.time.Instant.now().atZone(java.time.ZoneOffset.UTC), operational, operational, false, null,
                "TEST", 30, 240, 10, "USD", new BigDecimal("0.25"), 60, null, 60, 60L,
                java.time.Instant.now().plusSeconds(3600), operational, reason, tariffId);
    }

}
