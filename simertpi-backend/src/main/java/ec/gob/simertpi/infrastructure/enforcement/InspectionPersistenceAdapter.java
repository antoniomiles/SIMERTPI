package ec.gob.simertpi.infrastructure.enforcement;

import ec.gob.simertpi.domain.enforcement.entity.Inspection;
import ec.gob.simertpi.domain.enforcement.repository.InspectionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InspectionPersistenceAdapter implements InspectionRepository {

    private final JpaInspectionRepository repository;

    public InspectionPersistenceAdapter(
            JpaInspectionRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public Inspection save(Inspection inspection) {
        return repository.save(inspection);
    }

    @Override
    public Optional<Inspection> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public List<Inspection> findByInspectorId(UUID inspectorId) {
        return repository.findByInspectorId(inspectorId);
    }

    @Override
    public List<Inspection> findByParkingSessionId(UUID parkingSessionId) {
        return repository.findByParkingSessionId(parkingSessionId);
    }

    @Override
    public List<Inspection> findByParkingSpaceId(UUID parkingSpaceId) {
        return repository.findByParkingSpaceId(parkingSpaceId);
    }

    @Override
    public List<Inspection> findByVehicleId(UUID vehicleId) {
        return repository.findByVehicleId(vehicleId);
    }
}