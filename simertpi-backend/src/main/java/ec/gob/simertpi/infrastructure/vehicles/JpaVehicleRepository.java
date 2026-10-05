package ec.gob.simertpi.infrastructure.vehicles;

import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaVehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByPlateIgnoreCase(String plate);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select v from Vehicle v where v.id = :id")
    Optional<Vehicle> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    List<Vehicle> findByUserId(UUID userId);
}
