package ec.gob.simertpi.infrastructure.enforcement;

import ec.gob.simertpi.domain.enforcement.entity.Violation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaViolationRepository extends JpaRepository<Violation, UUID> {

    List<Violation> findByInspectionId(UUID inspectionId);

    List<Violation> findByInspectorId(UUID inspectorId);

    List<Violation> findByParkingSessionId(UUID parkingSessionId);

    List<Violation> findByParkingSpaceId(UUID parkingSpaceId);

    List<Violation> findByVehicleId(UUID vehicleId);

    List<Violation> findByPlate(String plate);

    List<Violation> findByStatus(String status);
}
