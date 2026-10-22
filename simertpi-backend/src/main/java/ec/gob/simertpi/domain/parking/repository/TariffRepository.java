package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Tariff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

public interface TariffRepository extends JpaRepository<Tariff, UUID> {

    List<Tariff> findAllByCodeOrderByValidFromDesc(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    boolean existsByCodeAndVersionGroupIdNot(String code, UUID versionGroupId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tariff t WHERE t.id = :id")
    Optional<Tariff> findByIdForUpdate(@Param("id") UUID id);

    List<Tariff> findByActiveTrue();

    @Query("""
        SELECT t
        FROM Tariff t
        WHERE t.active = true
          AND t.validFrom <= :dateTime
          AND (t.validTo IS NULL OR t.validTo >= :dateTime)
        ORDER BY t.validFrom DESC
        """)
    List<Tariff> findActiveTariffsAt(
            @Param("dateTime") OffsetDateTime dateTime
    );
}
