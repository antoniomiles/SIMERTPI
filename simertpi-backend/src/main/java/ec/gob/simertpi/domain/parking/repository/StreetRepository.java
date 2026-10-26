package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Street;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StreetRepository extends JpaRepository<Street, UUID> {

    Optional<Street> findByZoneIdAndCode(UUID zoneId, String code);

    boolean existsByZoneIdAndCode(UUID zoneId, String code);

    List<Street> findByZoneId(UUID zoneId);

    boolean existsByZoneIdAndCodeAndIdNot(UUID zoneId, String code, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Street s where s.id = :id")
    Optional<Street> findByIdForUpdate(@Param("id") UUID id);
}
