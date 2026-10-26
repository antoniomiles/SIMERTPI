package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Zone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface ZoneRepository extends JpaRepository<Zone, UUID> {

    Optional<Zone> findByCode(String code);

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select z from Zone z where z.id = :id")
    Optional<Zone> findByIdForUpdate(@Param("id") UUID id);
}
