package ec.gob.simertpi.infrastructure.vehicles;

import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VehiclePersistenceAdapter implements VehicleRepository {

    private final JpaVehicleRepository jpaVehicleRepository;

    public VehiclePersistenceAdapter(JpaVehicleRepository jpaVehicleRepository) {
        this.jpaVehicleRepository = jpaVehicleRepository;
    }

    @Override
    public Vehicle save(Vehicle vehicle) {
        return jpaVehicleRepository.save(vehicle);
    }

    @Override
    public Optional<Vehicle> findById(UUID id) {
        return jpaVehicleRepository.findById(id);
    }

    @Override
    public Optional<Vehicle> findByIdForUpdate(UUID id) {
        return jpaVehicleRepository.findByIdForUpdate(id);
    }

    @Override
    public Optional<Vehicle> findByPlate(String plate) {
        var matches = jpaVehicleRepository.findByPlateIgnoreCase(plate.trim());
        if (matches.size() > 1) {
            throw new ec.gob.simertpi.domain.vehicles.VehicleAssociationConflictException();
        }
        return matches.stream().findFirst();
    }

    @Override
    public List<Vehicle> findByUserId(UUID userId) {
        return jpaVehicleRepository.findByUserId(userId);
    }
}
