package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Tariff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TariffRepository extends JpaRepository<Tariff, UUID> {

    Optional<Tariff> findByCode(String code);

    boolean existsByCode(String code);

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