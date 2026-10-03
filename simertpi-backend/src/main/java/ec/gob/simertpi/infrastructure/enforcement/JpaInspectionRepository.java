package ec.gob.simertpi.infrastructure.enforcement;

import ec.gob.simertpi.domain.enforcement.entity.Inspection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaInspectionRepository extends JpaRepository<Inspection, UUID> {

    List<Inspection> findByInspectorId(UUID inspectorId);

    List<Inspection> findByParkingSessionId(UUID parkingSessionId);

    List<Inspection> findByParkingSpaceId(UUID parkingSpaceId);

    List<Inspection> findByVehicleId(UUID vehicleId);
}