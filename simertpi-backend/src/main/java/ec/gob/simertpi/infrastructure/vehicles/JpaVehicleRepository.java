package ec.gob.simertpi.infrastructure.vehicles;

import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaVehicleRepository extends JpaRepository<Vehicle, UUID> {

    Optional<Vehicle> findByPlate(String plate);

    List<Vehicle> findByUserId(UUID userId);
}
