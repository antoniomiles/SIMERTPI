package ec.gob.simertpi.domain.enforcement.repository;

import ec.gob.simertpi.domain.enforcement.entity.Violation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ViolationRepository {

    Violation save(Violation violation);

    Optional<Violation> findById(UUID id);

    List<Violation> findByInspectionId(UUID inspectionId);

    List<Violation> findByInspectorId(UUID inspectorId);

    List<Violation> findByParkingSessionId(UUID parkingSessionId);

    List<Violation> findByParkingSpaceId(UUID parkingSpaceId);

    List<Violation> findByVehicleId(UUID vehicleId);

    List<Violation> findByPlate(String plate);

    List<Violation> findByStatus(String status);
}