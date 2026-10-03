package ec.gob.simertpi.infrastructure.enforcement;

import ec.gob.simertpi.domain.enforcement.entity.Violation;
import ec.gob.simertpi.domain.enforcement.repository.ViolationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ViolationPersistenceAdapter implements ViolationRepository {

    private final JpaViolationRepository repository;

    public ViolationPersistenceAdapter(JpaViolationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Violation save(Violation violation) {
        return repository.save(violation);
    }

    @Override
    public Optional<Violation> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public List<Violation> findByInspectionId(UUID inspectionId) {
        return repository.findByInspectionId(inspectionId);
    }

    @Override
    public List<Violation> findByInspectorId(UUID inspectorId) {
        return repository.findByInspectorId(inspectorId);
    }

    @Override
    public List<Violation> findByParkingSessionId(UUID parkingSessionId) {
        return repository.findByParkingSessionId(parkingSessionId);
    }

    @Override
    public List<Violation> findByParkingSpaceId(UUID parkingSpaceId) {
        return repository.findByParkingSpaceId(parkingSpaceId);
    }

    @Override
    public List<Violation> findByVehicleId(UUID vehicleId) {
        return repository.findByVehicleId(vehicleId);
    }

    @Override
    public List<Violation> findByPlate(String plate) {
        return repository.findByPlate(plate);
    }

    @Override
    public List<Violation> findByStatus(String status) {
        return repository.findByStatus(status);
    }
}
