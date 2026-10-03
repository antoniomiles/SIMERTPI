package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.api.enforcement.dto.InspectionSituationResponse;
import ec.gob.simertpi.domain.enforcement.entity.Inspection;
import ec.gob.simertpi.domain.enforcement.repository.InspectionRepository;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import ec.gob.simertpi.domain.parking.entity.ParkingSessionStatus;
import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import ec.gob.simertpi.domain.parking.repository.ParkingControlEventRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSessionRepository;
import ec.gob.simertpi.domain.parking.repository.ParkingSpaceRepository;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional
public class InspectionService {
    private static final java.util.Set<String> VALID_RESULTS = java.util.Set.of(
            "VALID", "EXPIRED", "NO_PAYMENT", "VIOLATION");

    private final InspectionRepository inspections;
    private final InspectorAuthorizationService authorization;
    private final ParkingSessionRepository sessions;
    private final ParkingSpaceRepository spaces;
    private final VehicleRepository vehicles;
    private final ParkingControlEventRepository controlEvents;

    public InspectionService(InspectionRepository inspections,
                             InspectorAuthorizationService authorization,
                             ParkingSessionRepository sessions,
                             ParkingSpaceRepository spaces,
                             VehicleRepository vehicles,
                             ParkingControlEventRepository controlEvents) {
        this.inspections = inspections;
        this.authorization = authorization;
        this.sessions = sessions;
        this.spaces = spaces;
        this.vehicles = vehicles;
        this.controlEvents = controlEvents;
    }

    public Inspection create(String username, UUID parkingSessionId, UUID parkingSpaceId,
                             UUID vehicleId, String observedPlate, OffsetDateTime observedAt,
                             String result, String notes, BigDecimal latitude, BigDecimal longitude) {
        User inspector = authorization.requireInspector(username);
        if (parkingSpaceId == null) throw new InvalidRequestException("Parking space is required");
        ParkingSpace space = spaces.findById(parkingSpaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking space not found"));
        if (!space.isActive()) throw new IllegalArgumentException("Parking space is inactive");

        ParkingSession session = parkingSessionId == null ? null : sessions.findById(parkingSessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Parking session not found"));
        if (session != null && !space.getId().equals(session.getParkingSpaceId())) {
            throw new InvalidRequestException("Parking session does not belong to parking space");
        }

        String plate = normalizePlate(observedPlate);
        Vehicle vehicle = vehicleId == null ? null : vehicles.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
        if (session != null) {
            if (vehicle != null && !vehicle.getId().equals(session.getVehicleId())) {
                throw new InvalidRequestException("Vehicle does not belong to parking session");
            }
            vehicle = vehicles.findById(session.getVehicleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Session vehicle not found"));
        } else if (vehicle == null && plate != null) {
            vehicle = vehicles.findByPlate(plate).orElse(null);
        }
        if (vehicle != null) {
            if (plate != null && !plate.equalsIgnoreCase(vehicle.getPlate())) {
                throw new InvalidRequestException("Observed plate does not match vehicle");
            }
            if (plate == null) plate = vehicle.getPlate();
        }
        if (session == null && vehicle == null && plate == null) {
            throw new InvalidRequestException("Inspection must identify a vehicle or observed plate");
        }
        if (result == null || !VALID_RESULTS.contains(result)) {
            throw new InvalidRequestException("Invalid inspection result");
        }
        if (observedAt == null) throw new InvalidRequestException("Observed date and time are required");

        Inspection inspection = new Inspection();
        inspection.setId(UUID.randomUUID());
        inspection.setInspectorId(inspector.getId());
        inspection.setParkingSessionId(session == null ? null : session.getId());
        inspection.setParkingSpaceId(space.getId());
        inspection.setVehicleId(vehicle == null ? null : vehicle.getId());
        inspection.setObservedPlate(plate);
        inspection.setObservedAt(observedAt);
        inspection.setResult(result);
        inspection.setNotes(notes);
        inspection.setLatitude(latitude);
        inspection.setLongitude(longitude);
        inspection.setCreatedAt(OffsetDateTime.now());
        return inspections.save(inspection);
    }

    @Transactional(readOnly = true)
    public Inspection findById(String username, UUID id) {
        User inspector = authorization.requireInspector(username);
        Inspection inspection = inspections.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
        requireOwner(inspection.getInspectorId(), inspector);
        return inspection;
    }

    @Transactional(readOnly = true)
    public List<Inspection> findByInspectorId(String username, UUID inspectorId) {
        User inspector = authorization.requireInspector(username);
        requireOwner(inspectorId, inspector);
        return inspections.findByInspectorId(inspectorId);
    }

    @Transactional(readOnly = true)
    public List<Inspection> findByParkingSessionId(String username, UUID id) {
        authorization.requireInspector(username);
        return inspections.findByParkingSessionId(id);
    }

    @Transactional(readOnly = true)
    public List<Inspection> findByParkingSpaceId(String username, UUID id) {
        authorization.requireInspector(username);
        return inspections.findByParkingSpaceId(id);
    }

    @Transactional(readOnly = true)
    public List<Inspection> findByVehicleId(String username, UUID id) {
        authorization.requireInspector(username);
        return inspections.findByVehicleId(id);
    }

    @Transactional(readOnly = true)
    public InspectionSituationResponse lookup(String username, String plate, String qrCode,
                                               UUID parkingSpaceId) {
        authorization.requireInspector(username);
        int selectorCount = (plate == null || plate.isBlank() ? 0 : 1)
                + (qrCode == null || qrCode.isBlank() ? 0 : 1)
                + (parkingSpaceId == null ? 0 : 1);
        if (selectorCount != 1) {
            throw new InvalidRequestException("Provide exactly one of plate, qrCode or parkingSpaceId");
        }

        ParkingSpace space = null;
        Vehicle vehicle = null;
        ParkingSession session = null;
        String observedPlate = normalizePlate(plate);
        if (qrCode != null && !qrCode.isBlank()) {
            space = spaces.findByQrCode(qrCode.trim())
                    .orElseThrow(() -> new ResourceNotFoundException("Parking space QR code not found"));
        } else if (parkingSpaceId != null) {
            space = spaces.findById(parkingSpaceId)
                    .orElseThrow(() -> new ResourceNotFoundException("Parking space not found"));
        } else {
            vehicle = vehicles.findByPlate(observedPlate).orElse(null);
            if (vehicle != null) {
                session = sessions.findByVehicleId(vehicle.getId()).stream()
                        .filter(s -> ParkingSessionStatus.occupyingCodes().contains(s.getStatus()))
                        .max(java.util.Comparator.comparing(ParkingSession::getStartedAt)).orElse(null);
            }
        }
        if (space != null) {
            session = sessions.findFirstByParkingSpaceIdAndStatusInOrderByStartedAtDesc(
                    space.getId(), ParkingSessionStatus.occupyingCodes()).orElse(null);
            if (session != null) {
                vehicle = vehicles.findById(session.getVehicleId()).orElse(null);
                if (vehicle != null) observedPlate = vehicle.getPlate();
            }
        }
        if (session != null && space == null) {
            space = spaces.findById(session.getParkingSpaceId()).orElse(null);
        }
        List<InspectionSituationResponse.ControlEventSummary> events = session == null ? List.of()
                : controlEvents.findByParkingSessionIdOrderByOccurredAtAsc(session.getId()).stream()
                .map(event -> new InspectionSituationResponse.ControlEventSummary(
                        event.getEventType(), event.getOccurredAt(), event.getMinutesOverdue(), event.getStatus()))
                .toList();
        return new InspectionSituationResponse(
                space == null ? null : space.getId(), space == null ? null : space.getCode(),
                space == null ? null : space.getQrCode(), vehicle == null ? null : vehicle.getId(),
                observedPlate, session == null ? null : session.getId(),
                session == null ? null : session.getStatus(), session == null ? null : session.getStartedAt(),
                session == null ? null : session.getExpectedEndAt(), session == null ? null : session.getEndedAt(), events);
    }

    private String normalizePlate(String plate) {
        return plate == null || plate.isBlank() ? null : plate.trim().toUpperCase(Locale.ROOT);
    }

    private void requireOwner(UUID ownerId, User user) {
        if (!ownerId.equals(user.getId())) throw new ForbiddenException();
    }
}
