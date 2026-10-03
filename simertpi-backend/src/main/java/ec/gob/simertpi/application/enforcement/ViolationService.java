package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.enforcement.entity.Inspection;
import ec.gob.simertpi.domain.enforcement.entity.Violation;
import ec.gob.simertpi.domain.enforcement.repository.InspectionRepository;
import ec.gob.simertpi.domain.enforcement.repository.ViolationRepository;
import ec.gob.simertpi.domain.identity.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ViolationService {
    private static final java.util.Set<String> VALID_STATUSES = java.util.Set.of(
            "OPEN", "NOTIFIED", "PAID", "CANCELLED", "DISPUTED");
    private final ViolationRepository violations;
    private final InspectionRepository inspections;
    private final InspectorAuthorizationService authorization;

    public ViolationService(ViolationRepository violations, InspectionRepository inspections,
                            InspectorAuthorizationService authorization) {
        this.violations = violations;
        this.inspections = inspections;
        this.authorization = authorization;
    }

    public Violation create(String username, UUID inspectionId, String violationType,
                            String description, OffsetDateTime occurredAt) {
        User inspector = authorization.requireInspector(username);
        Inspection inspection = inspections.findById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
        requireOwner(inspection.getInspectorId(), inspector);
        if ("VALID".equals(inspection.getResult())) {
            throw new IllegalArgumentException("A valid inspection cannot generate a violation");
        }
        if (violations.findByInspectionId(inspectionId).stream().findAny().isPresent()) {
            throw new IllegalArgumentException("Inspection already has a violation");
        }
        if (violationType == null || violationType.isBlank() || violationType.length() > 50) {
            throw new InvalidRequestException("Violation type is required and must be at most 50 characters");
        }
        if (occurredAt == null) throw new InvalidRequestException("Occurrence date and time are required");

        OffsetDateTime now = OffsetDateTime.now();
        Violation violation = new Violation();
        violation.setId(UUID.randomUUID());
        violation.setInspectionId(inspection.getId());
        violation.setInspectorId(inspector.getId());
        violation.setParkingSessionId(inspection.getParkingSessionId());
        violation.setParkingSpaceId(inspection.getParkingSpaceId());
        violation.setVehicleId(inspection.getVehicleId());
        violation.setPlate(inspection.getObservedPlate());
        violation.setViolationType(violationType.trim());
        violation.setDescription(description);
        violation.setOccurredAt(occurredAt);
        violation.setStatus("OPEN");
        violation.setFineAmount(BigDecimal.ZERO);
        violation.setNotifiedAt(null);
        violation.setPaidAt(null);
        violation.setCreatedAt(now);
        violation.setUpdatedAt(now);
        return violations.save(violation);
    }

    @Audited(action = "VIOLATION_STATUS_CHANGED", resourceType = "VIOLATION", resourceIdArgument = 1)
    public Violation changeStatus(String username, UUID violationId, String newStatus) {
        User inspector = authorization.requireInspector(username);
        Violation violation = violations.findById(violationId)
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found"));
        requireOwner(violation.getInspectorId(), inspector);
        if (newStatus == null || !VALID_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException("Invalid violation status");
        }
        String current = violation.getStatus();
        boolean allowed = switch (current) {
            case "OPEN" -> "NOTIFIED".equals(newStatus) || "CANCELLED".equals(newStatus);
            case "NOTIFIED" -> "PAID".equals(newStatus) || "DISPUTED".equals(newStatus)
                    || "CANCELLED".equals(newStatus);
            case "DISPUTED" -> "PAID".equals(newStatus);
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("Invalid violation status transition");
        OffsetDateTime now = OffsetDateTime.now();
        violation.setStatus(newStatus);
        if ("NOTIFIED".equals(newStatus)) violation.setNotifiedAt(now);
        if ("PAID".equals(newStatus)) violation.setPaidAt(now);
        violation.setUpdatedAt(now);
        return violations.save(violation);
    }

    @Transactional(readOnly = true)
    public Violation findById(String username, UUID id) {
        User inspector = authorization.requireInspector(username);
        Violation violation = violations.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Violation not found"));
        requireOwner(violation.getInspectorId(), inspector);
        return violation;
    }

    @Transactional(readOnly = true)
    public List<Violation> findByInspectionId(String username, UUID id) {
        User inspector = authorization.requireInspector(username);
        Inspection inspection = inspections.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
        requireOwner(inspection.getInspectorId(), inspector);
        return violations.findByInspectionId(id);
    }

    @Transactional(readOnly = true)
    public List<Violation> findByInspectorId(String username, UUID id) {
        User inspector = authorization.requireInspector(username);
        requireOwner(id, inspector);
        return violations.findByInspectorId(id);
    }

    @Transactional(readOnly = true)
    public List<Violation> findByParkingSessionId(String username, UUID id) {
        authorization.requireInspector(username); return violations.findByParkingSessionId(id);
    }
    @Transactional(readOnly = true)
    public List<Violation> findByParkingSpaceId(String username, UUID id) {
        authorization.requireInspector(username); return violations.findByParkingSpaceId(id);
    }
    @Transactional(readOnly = true)
    public List<Violation> findByVehicleId(String username, UUID id) {
        authorization.requireInspector(username); return violations.findByVehicleId(id);
    }
    @Transactional(readOnly = true)
    public List<Violation> findByPlate(String username, String plate) {
        authorization.requireInspector(username); return violations.findByPlate(plate);
    }
    @Transactional(readOnly = true)
    public List<Violation> findByStatus(String username, String status) {
        authorization.requireInspector(username);
        if (status == null || !VALID_STATUSES.contains(status)) throw new IllegalArgumentException("Invalid violation status");
        return violations.findByStatus(status);
    }

    private void requireOwner(UUID ownerId, User inspector) {
        if (!inspector.getId().equals(ownerId)) throw new ForbiddenException();
    }
}
