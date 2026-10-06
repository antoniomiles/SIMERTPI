package ec.gob.simertpi.api.security;

import ec.gob.simertpi.api.GlobalExceptionHandler;
import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.parking.ParkingSessionController;
import ec.gob.simertpi.application.parking.ParkingSessionService;
import ec.gob.simertpi.application.parking.IdempotentParkingSessionCreationService;
import ec.gob.simertpi.config.SecurityConfig;
import ec.gob.simertpi.config.SimertpiUserDetailsService;
import ec.gob.simertpi.domain.identity.entity.Role;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ParkingSessionController.class)
@Import({SecurityConfig.class, SimertpiUserDetailsService.class, GlobalExceptionHandler.class})
class HttpBasicSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ParkingSessionService parkingSessionService;
    @MockitoBean private IdempotentParkingSessionCreationService idempotentCreationService;
    @MockitoBean private ParkingSpaceRepository parkingSpaceRepository;
    @MockitoBean private VehicleRepository vehicleRepository;
    @MockitoBean private TariffRepository tariffRepository;
    @MockitoBean private ec.gob.simertpi.application.parking.rules.ParkingRulesService rules;
    @MockitoBean private ec.gob.simertpi.application.parking.ParkingAvailabilityService availability;

    private UUID userId;
    private UUID vehicleId;
    private UUID spaceId;
    private UUID tariffId;
    private ParkingSession session;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        vehicleId = UUID.randomUUID();
        spaceId = UUID.randomUUID();
        tariffId = UUID.randomUUID();

        Role citizen = new Role();
        citizen.setCode("CITIZEN");
        User user = new User();
        user.setId(userId);
        user.setUsername("citizen-a");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        user.setEnabled(true);
        user.setRoles(Set.of(citizen));
        when(userRepository.findByUsernameWithRoles("citizen-a")).thenReturn(Optional.of(user));

        session = new ParkingSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setVehicleId(vehicleId);
        session.setParkingSpaceId(spaceId);
        session.setTariffId(tariffId);
        session.setStartedAt(OffsetDateTime.now());
        session.setExpectedEndAt(session.getStartedAt().plusMinutes(60));
        session.setStatus("PENDING_PAYMENT");
        when(rules.lifecycle(any(),any(),org.mockito.ArgumentMatchers.anyLong())).thenReturn(
                new ec.gob.simertpi.application.parking.rules.SessionLifecycle.View("PENDING_PAYMENT",java.time.Instant.now(),null,false,false));
        session.setTotalAmount(BigDecimal.ZERO);
        session.setCreatedAt(session.getStartedAt());
        session.setUpdatedAt(session.getStartedAt());
        when(idempotentCreationService.create(eq("citizen-a"), anyString(), eq("QR-A"),
                eq(vehicleId), eq(tariffId), eq(60))).thenReturn(session);

        ParkingSpace space = new ParkingSpace();
        space.setId(spaceId);
        space.setCode("SPACE-A");
        space.setQrCode("QR-A");
        when(parkingSpaceRepository.findById(spaceId)).thenReturn(Optional.of(space));
        Vehicle vehicle = new Vehicle();
        vehicle.setId(vehicleId);
        vehicle.setPlate("ABC1234");
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        Tariff tariff = new Tariff();
        tariff.setId(tariffId);
        tariff.setName("Test tariff");
        when(tariffRepository.findById(tariffId)).thenReturn(Optional.of(tariff));
    }

    @Test
    void rejectsRequestWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/parking/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownUsername() throws Exception {
        when(userRepository.findByUsernameWithRoles("unknown")).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/parking/sessions").with(httpBasic("unknown", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDisabledUser() throws Exception {
        when(userRepository.findByUsernameWithRoles("citizen-a")).thenAnswer(invocation -> {
            User disabled = new User();
            disabled.setUsername("citizen-a");
            disabled.setPasswordHash(passwordEncoder.encode("correct-password"));
            disabled.setEnabled(false);
            disabled.setRoles(Set.of());
            return Optional.of(disabled);
        });
        mockMvc.perform(post("/api/v1/parking/sessions").with(httpBasic("citizen-a", "correct-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsIncorrectPassword() throws Exception {
        mockMvc.perform(post("/api/v1/parking/sessions").with(httpBasic("citizen-a", "wrong-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsCorrectPasswordAndUsesAuthenticatedUsername() throws Exception {
        mockMvc.perform(post("/api/v1/parking/sessions").with(httpBasic("citizen-a", "correct-password"))
                        .header("Idempotency-Key", "KEY-A")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isCreated());
        verify(idempotentCreationService).create("citizen-a", "KEY-A", "QR-A", vehicleId, tariffId, 60);
    }

    @Test
    void rejectsAuthenticatedUserWithoutCitizenRole() throws Exception {
        when(userRepository.findByUsernameWithRoles("citizen-a")).thenAnswer(invocation -> {
            User user = new User();
            user.setUsername("citizen-a");
            user.setPasswordHash(passwordEncoder.encode("correct-password"));
            user.setEnabled(true);
            user.setRoles(Set.of());
            return Optional.of(user);
        });
        mockMvc.perform(post("/api/v1/parking/sessions").with(httpBasic("citizen-a", "correct-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsForbiddenWhenAuthenticatedCitizenUsesAnotherUsersVehicle() throws Exception {
        doThrow(new ForbiddenException()).when(idempotentCreationService)
                .create(eq("citizen-a"), anyString(), eq("QR-A"), any(UUID.class), eq(tariffId), eq(60));
        mockMvc.perform(post("/api/v1/parking/sessions").with(httpBasic("citizen-a", "correct-password"))
                        .header("Idempotency-Key", "KEY-A")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isForbidden());
    }

    private String requestBody() {
        return """
                {"parkingSpaceQrCode":"QR-A","vehicleId":"%s","tariffId":"%s","durationMinutes":60}
                """.formatted(vehicleId, tariffId);
    }
}
