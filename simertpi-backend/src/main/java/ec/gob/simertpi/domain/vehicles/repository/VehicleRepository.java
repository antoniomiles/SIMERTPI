package ec.gob.simertpi.domain.vehicles.repository;

import ec.gob.simertpi.domain.vehicles.entity.Vehicle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository {

    Vehicle save(Vehicle vehicle);

    Optional<Vehicle> findById(UUID id);

    Optional<Vehicle> findByPlate(String plate);

    List<Vehicle> findByUserId(UUID userId);
}
