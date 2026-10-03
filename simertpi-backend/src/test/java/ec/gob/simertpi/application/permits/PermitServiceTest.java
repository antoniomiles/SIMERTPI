package ec.gob.simertpi.application.permits;

import com.fasterxml.jackson.databind.ObjectMapper;
import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.permits.dto.CreatePermitRequest;
import ec.gob.simertpi.application.idempotency.IdempotencyStore;
import ec.gob.simertpi.application.permits.PermitEventPublisher;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import ec.gob.simertpi.domain.permits.entity.Permit;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import ec.gob.simertpi.infrastructure.permits.JpaPermitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermitServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID VEHICLE_ID = UUID.randomUUID();

    @Mock private JpaPermitRepository permits;
    @Mock private UserRepository users;
    @Mock private VehicleRepository vehicles;
    @Mock private ZoneRepository zones;
    @Mock private IdempotencyStore idempotency;
    @Mock private PermitEventPublisher events;
    @Mock private EntityManager entityManager;
    private PermitService service;

    @BeforeEach
    void setUp() {
        service = new PermitService(permits, users, vehicles, zones, idempotency, events,
                new ObjectMapper(), entityManager);
    }

    @Test
    void createsActivePermitForAuthorizedAdminAndMatchingActiveVehicle() {
        User beneficiary = user(USER_ID);
        Vehicle vehicle = vehicle(VEHICLE_ID, USER_ID);
        when(users.findByUsername("admin")).thenReturn(Optional.of(user(UUID.randomUUID())));
        when(users.findById(USER_ID)).thenReturn(Optional.of(beneficiary));
        when(entityManager.find(Vehicle.class, VEHICLE_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(vehicle);
        when(permits.existsOverlapping(eq(VEHICLE_ID), isNull(), any(), any())).thenReturn(false);
        when(permits.saveAndFlush(any(Permit.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Permit created = service.create("admin", Set.of("SIMERTPI_ADMIN"), null, request(
                OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now().plusDays(1)));

        assertEquals("ACTIVE", created.getStatus());
        assertEquals(USER_ID, created.getUserId());
        assertEquals(VEHICLE_ID, created.getVehicleId());
        verify(permits).saveAndFlush(any(Permit.class));
    }

    @Test
    void createsFuturePermitAsPending() {
        mockValidCreation();
        when(permits.existsOverlapping(eq(VEHICLE_ID), isNull(), any(), any())).thenReturn(false);
        when(permits.saveAndFlush(any(Permit.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Permit created = service.create("admin", Set.of("SIMERTPI_ADMIN"), null,
                request(OffsetDateTime.now().plusDays(1), OffsetDateTime.now().plusDays(2)));
        assertEquals("PENDING", created.getStatus());
    }

    @Test
    void rejectsUnknownBeneficiary() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(user(UUID.randomUUID())));
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.create("admin",
                Set.of("SIMERTPI_ADMIN"), null,
                request(OffsetDateTime.now(), OffsetDateTime.now().plusDays(1))));
        verifyNoInteractions(entityManager);
    }

    @Test
    void rejectsMissingOrInactiveVehicle() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(user(UUID.randomUUID())));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(entityManager.find(Vehicle.class, VEHICLE_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.create("admin",
                Set.of("SIMERTPI_ADMIN"), null,
                request(OffsetDateTime.now(), OffsetDateTime.now().plusDays(1))));
        verify(permits, never()).saveAndFlush(any());
    }

    @Test
    void rejectsVehicleOwnedByAnotherBeneficiary() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(user(UUID.randomUUID())));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(entityManager.find(Vehicle.class, VEHICLE_ID, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(vehicle(VEHICLE_ID, UUID.randomUUID()));
        assertThrows(InvalidRequestException.class, () -> service.create("admin",
                Set.of("SIMERTPI_ADMIN"), null,
                request(OffsetDateTime.now(), OffsetDateTime.now().plusDays(1))));
        verify(permits, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidDatesAndOverlappingPermits() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(user(UUID.randomUUID())));
        assertThrows(InvalidRequestException.class, () -> service.create("admin",
                Set.of("SIMERTPI_ADMIN"), null,
                request(OffsetDateTime.now(), OffsetDateTime.now().minusSeconds(1))));
        mockValidCreation();
        when(permits.existsOverlapping(eq(VEHICLE_ID), isNull(), any(), any())).thenReturn(true);
        assertThrows(InvalidRequestException.class, () -> service.create("admin",
                Set.of("SIMERTPI_ADMIN"), null,
                request(OffsetDateTime.now(), OffsetDateTime.now().plusDays(1))));
        verify(permits, never()).saveAndFlush(any());
    }

    @Test
    void citizenCanReadOwnPermitButCannotReadAnotherUsersPermit() {
        Permit permit = permit(UUID.randomUUID(), USER_ID, "ACTIVE");
        when(users.findByUsername("citizen")).thenReturn(Optional.of(user(USER_ID)));
        when(permits.findById(permit.getId())).thenReturn(Optional.of(permit));
        assertSame(permit, service.byId("citizen", permit.getId(), Set.of("CITIZEN")));

        when(users.findByUsername("other")).thenReturn(Optional.of(user(UUID.randomUUID())));
        assertThrows(ForbiddenException.class,
                () -> service.byId("other", permit.getId(), Set.of("CITIZEN")));
    }

    @Test
    void municipalOperatorCanReadPermitButCitizenCannotCancel() {
        Permit permit = permit(UUID.randomUUID(), USER_ID, "ACTIVE");
        when(users.findByUsername("inspector")).thenReturn(Optional.of(user(UUID.randomUUID())));
        when(permits.findById(permit.getId())).thenReturn(Optional.of(permit));
        assertSame(permit, service.byId("inspector", permit.getId(), Set.of("INSPECTOR")));
        assertThrows(ForbiddenException.class,
                () -> service.cancel("citizen", permit.getId(), Set.of("CITIZEN")));
    }

    @Test
    void cancellationIsLogicalAndOnlyAdminCanPerformIt() {
        Permit permit = permit(UUID.randomUUID(), USER_ID, "ACTIVE");
        when(users.findByUsername("admin")).thenReturn(Optional.of(user(UUID.randomUUID())));
        when(permits.findById(permit.getId())).thenReturn(Optional.of(permit));
        when(permits.save(permit)).thenReturn(permit);
        Permit cancelled = service.cancel("admin", permit.getId(), Set.of("SIMERTPI_ADMIN"));
        assertEquals("CANCELLED", cancelled.getStatus());
        assertNotNull(cancelled.getUpdatedAt());
    }

    @Test
    void expiresActiveAndPendingPermitsIdempotentlyAndActivatesDuePermit() {
        OffsetDateTime now = OffsetDateTime.now();
        Permit active = permit(UUID.randomUUID(), USER_ID, "ACTIVE");
        Permit pending = permit(UUID.randomUUID(), USER_ID, "PENDING");
        when(permits.findExpiredActive(now)).thenReturn(List.of(active, pending));
        when(permits.findPendingToActivate(now)).thenReturn(List.of());
        assertEquals(2, service.expireDue(now));
        assertEquals("EXPIRED", active.getStatus());
        assertEquals("EXPIRED", pending.getStatus());
        assertEquals(0, service.activateDue(now));
    }

    private void mockValidCreation() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(user(UUID.randomUUID())));
        when(users.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(entityManager.find(Vehicle.class, VEHICLE_ID, LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(vehicle(VEHICLE_ID, USER_ID));
    }

    private CreatePermitRequest request(OffsetDateTime from, OffsetDateTime to) {
        return new CreatePermitRequest(USER_ID, VEHICLE_ID, null, "RESIDENT", from, to, "AUTH-001", null);
    }
    private User user(UUID id) { User user = new User(); user.setId(id); user.setUsername("user-" + id); user.setEnabled(true); return user; }
    private Vehicle vehicle(UUID id, UUID owner) { Vehicle vehicle = new Vehicle(); vehicle.setId(id); vehicle.setUserId(owner); vehicle.setActive(true); return vehicle; }
    private Permit permit(UUID id, UUID owner, String status) {
        Permit permit = new Permit(); permit.setId(id); permit.setUserId(owner); permit.setVehicleId(VEHICLE_ID);
        permit.setStatus(status); permit.setValidFrom(OffsetDateTime.now().minusDays(1));
        permit.setValidTo(OffsetDateTime.now().plusDays(1)); return permit;
    }
}
