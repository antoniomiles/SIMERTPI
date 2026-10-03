package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.enforcement.entity.Evidence;
import ec.gob.simertpi.domain.enforcement.entity.Inspection;
import ec.gob.simertpi.domain.enforcement.entity.Violation;
import ec.gob.simertpi.domain.enforcement.repository.EvidenceRepository;
import ec.gob.simertpi.domain.enforcement.repository.InspectionRepository;
import ec.gob.simertpi.domain.enforcement.repository.ViolationRepository;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.repository.ParkingControlEventRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnforcementServicesTest {
    @Mock InspectionRepository inspectionRepository;
    @Mock ViolationRepository violationRepository;
    @Mock EvidenceRepository evidenceRepository;
    @Mock ec.gob.simertpi.application.enforcement.storage.ObjectStorage storage;
    @Mock ec.gob.simertpi.application.audit.AuditService audit;
    @Mock ParkingSessionRepository sessionRepository;
    @Mock ParkingSpaceRepository spaceRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock ParkingControlEventRepository eventRepository;
    @Mock InspectorAuthorizationService authorization;

    UUID inspectorId;
    UUID spaceId;
    UUID vehicleId;
    UUID sessionId;
    UUID inspectionId;
    UUID violationId;
    User inspector;
    ParkingSpace space;
    Vehicle vehicle;
    ParkingSession session;
    Inspection inspection;
    Violation violation;
    InspectionService inspectionService;
    ViolationService violationService;
    EvidenceService evidenceService;

    @BeforeEach
    void setUp() {
        inspectorId = UUID.randomUUID(); spaceId = UUID.randomUUID(); vehicleId = UUID.randomUUID();
        sessionId = UUID.randomUUID(); inspectionId = UUID.randomUUID(); violationId = UUID.randomUUID();
        inspector = new User(); inspector.setId(inspectorId); inspector.setUsername("inspector"); inspector.setEnabled(true);
        space = new ParkingSpace(); space.setId(spaceId); space.setActive(true);
        vehicle = new Vehicle(); vehicle.setId(vehicleId); vehicle.setPlate("ABC123");
        session = new ParkingSession(); session.setId(sessionId); session.setParkingSpaceId(spaceId); session.setVehicleId(vehicleId);
        inspection = new Inspection(); inspection.setId(inspectionId); inspection.setInspectorId(inspectorId);
        inspection.setParkingSpaceId(spaceId); inspection.setVehicleId(vehicleId); inspection.setParkingSessionId(sessionId);
        inspection.setObservedPlate("ABC123"); inspection.setResult("NO_PAYMENT");
        violation = new Violation(); violation.setId(violationId); violation.setInspectorId(inspectorId);
        violation.setInspectionId(inspectionId);
        inspectionService = new InspectionService(inspectionRepository, authorization, sessionRepository,
                spaceRepository, vehicleRepository, eventRepository);
        violationService = new ViolationService(violationRepository, inspectionRepository, authorization);
        evidenceService = new EvidenceService(evidenceRepository, violationRepository, authorization, storage,
                new ec.gob.simertpi.infrastructure.storage.EvidenceStorageProperties(), audit);
        org.mockito.Mockito.lenient().when(authorization.requireInspector("inspector")).thenReturn(inspector);
    }

    @Test
    void createsInspectionFromAuthenticatedInspectorAndRealAssociations() {
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(inspectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Inspection saved = inspectionService.create("inspector", sessionId, spaceId, vehicleId,
                "ABC123", OffsetDateTime.now(), "NO_PAYMENT", null, null, null);

        assertThat(saved.getInspectorId()).isEqualTo(inspectorId);
        assertThat(saved.getParkingSessionId()).isEqualTo(sessionId);
        assertThat(saved.getVehicleId()).isEqualTo(vehicleId);
        assertThat(saved.getParkingSpaceId()).isEqualTo(spaceId);
    }

    @Test
    void rejectsInspectionWithoutSpaceOrValidVehicle() {
        assertThatThrownBy(() -> inspectionService.create("inspector", null, null, null,
                null, OffsetDateTime.now(), "NO_PAYMENT", null, null, null))
                .isInstanceOf(InvalidRequestException.class);
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> inspectionService.create("inspector", null, spaceId, vehicleId,
                null, OffsetDateTime.now(), "NO_PAYMENT", null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsInspectionWithMissingOrMismatchedSessionAndUnauthorizedUser() {
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> inspectionService.create("inspector", sessionId, spaceId, null,
                null, OffsetDateTime.now(), "NO_PAYMENT", null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        ParkingSession otherSpaceSession = new ParkingSession(); otherSpaceSession.setId(sessionId);
        otherSpaceSession.setParkingSpaceId(UUID.randomUUID()); otherSpaceSession.setVehicleId(vehicleId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(otherSpaceSession));
        assertThatThrownBy(() -> inspectionService.create("inspector", sessionId, spaceId, null,
                null, OffsetDateTime.now(), "NO_PAYMENT", null, null, null))
                .isInstanceOf(InvalidRequestException.class);
        when(authorization.requireInspector("citizen")).thenThrow(new ForbiddenException());
        assertThatThrownBy(() -> inspectionService.create("citizen", null, spaceId, null,
                "ABC123", OffsetDateTime.now(), "NO_PAYMENT", null, null, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void createsViolationUsingInspectionAssociationsAndZeroAmount() {
        when(inspectionRepository.findById(inspectionId)).thenReturn(Optional.of(inspection));
        when(violationRepository.findByInspectionId(inspectionId)).thenReturn(java.util.List.of());
        when(violationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Violation saved = violationService.create("inspector", inspectionId, "NO_PAYMENT", "Observed", OffsetDateTime.now());

        assertThat(saved.getInspectorId()).isEqualTo(inspectorId);
        assertThat(saved.getParkingSpaceId()).isEqualTo(spaceId);
        assertThat(saved.getParkingSessionId()).isEqualTo(sessionId);
        assertThat(saved.getVehicleId()).isEqualTo(vehicleId);
        assertThat(saved.getFineAmount()).isZero();
    }

    @Test
    void rejectsMissingValidOrDuplicateInspectionAndUnauthorizedViolation() {
        when(inspectionRepository.findById(inspectionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> violationService.create("inspector", inspectionId, "X", null, OffsetDateTime.now()))
                .isInstanceOf(ResourceNotFoundException.class);
        inspection.setResult("VALID");
        when(inspectionRepository.findById(inspectionId)).thenReturn(Optional.of(inspection));
        assertThatThrownBy(() -> violationService.create("inspector", inspectionId, "X", null, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        inspection.setResult("NO_PAYMENT");
        when(violationRepository.findByInspectionId(inspectionId)).thenReturn(java.util.List.of(violation));
        assertThatThrownBy(() -> violationService.create("inspector", inspectionId, "X", null, OffsetDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        when(authorization.requireInspector("citizen")).thenThrow(new ForbiddenException());
        assertThatThrownBy(() -> violationService.create("citizen", inspectionId, "X", null, OffsetDateTime.now()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void persistsRealSha256AndReusesDuplicate() throws Exception {
        when(violationRepository.findById(violationId)).thenReturn(Optional.of(violation));
        when(storage.putIfAbsent(any(), any(), any())).thenReturn(true);
        when(evidenceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var file = evidenceService.validateUpload("photo.jpg", "image/jpeg", new byte[]{(byte)255, (byte)216, (byte)255, 1});
        Evidence saved = evidenceService.upload("inspector", violationId, file, null, null, null);
        assertThat(saved.getSha256Hash()).isEqualTo(file.sha256()).matches("[0-9a-f]{64}");
        assertThat(saved.getStorageKey()).isEqualTo("violations/" + violationId + "/" + file.sha256() + ".jpg");
        when(evidenceRepository.findByViolationIdAndStorageKeyAndSha256Hash(violationId, saved.getStorageKey(), file.sha256()))
                .thenReturn(Optional.of(saved));
        assertThat(evidenceService.upload("inspector", violationId, file, null, null, null)).isSameAs(saved);
        verify(storage, times(1)).putIfAbsent(any(), any(), any());
        verify(audit, times(1)).success(eq("EVIDENCE_CREATED"), eq("EVIDENCE"), eq(saved.getId()), isNull(), any());
        assertThatThrownBy(() -> evidenceService.validateUpload("photo.jpg", "image/jpeg", new byte[]{1}))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsEvidenceForMissingViolationAndUnauthorizedInspector() {
        var file = evidenceService.validateUpload("photo.jpg", "image/jpeg", new byte[]{(byte)255, (byte)216, (byte)255});
        when(violationRepository.findById(violationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> evidenceService.upload("inspector", violationId, file, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        when(authorization.requireInspector("citizen")).thenThrow(new ForbiddenException());
        assertThatThrownBy(() -> evidenceService.upload("citizen", violationId, file, null, null, null))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(storage);
    }
}
