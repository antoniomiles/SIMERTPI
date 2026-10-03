package ec.gob.simertpi.domain.enforcement.repository;

import ec.gob.simertpi.domain.enforcement.entity.Inspection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InspectionRepository {

    Inspection save(Inspection inspection);

    Optional<Inspection> findById(UUID id);

    List<Inspection> findByInspectorId(UUID inspectorId);

    List<Inspection> findByParkingSessionId(UUID parkingSessionId);

    List<Inspection> findByParkingSpaceId(UUID parkingSpaceId);

    List<Inspection> findByVehicleId(UUID vehicleId);
}