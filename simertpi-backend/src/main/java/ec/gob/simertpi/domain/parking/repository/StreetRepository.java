package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Street;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StreetRepository extends JpaRepository<Street, UUID> {

    Optional<Street> findByZoneIdAndCode(UUID zoneId, String code);

    boolean existsByZoneIdAndCode(UUID zoneId, String code);

    List<Street> findByZoneId(UUID zoneId);
}
