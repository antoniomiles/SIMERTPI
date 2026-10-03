package ec.gob.simertpi.infrastructure.permits;

import ec.gob.simertpi.domain.permits.entity.Permit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface JpaPermitRepository extends JpaRepository<Permit, UUID> {
    List<Permit> findByUserIdOrderByValidFromDesc(UUID userId);
    List<Permit> findByVehicleIdOrderByValidFromDesc(UUID vehicleId);
    List<Permit> findByStatusAndValidToBetween(String status, OffsetDateTime from, OffsetDateTime to);

    @Query("select p from Permit p where p.status in ('ACTIVE','PENDING') and p.validTo <= :now")
    List<Permit> findExpiredActive(@Param("now") OffsetDateTime now);

    @Query("select p from Permit p where p.status = 'PENDING' and p.validFrom <= :now and p.validTo > :now")
    List<Permit> findPendingToActivate(@Param("now") OffsetDateTime now);

    @Query("select p from Permit p where p.status = 'ACTIVE' and p.validFrom <= :now and p.validTo > :now")
    List<Permit> findActive(@Param("now") OffsetDateTime now);

    @Query("select p from Permit p where p.status = 'EXPIRED' " +
            "or (p.status in ('ACTIVE','PENDING') and p.validTo <= :now) order by p.validTo desc")
    List<Permit> findExpired(@Param("now") OffsetDateTime now);

    @Query("select (count(p) > 0) from Permit p where p.vehicleId = :vehicleId " +
            "and (:zoneId is null or p.zoneId is null or p.zoneId = :zoneId) " +
            "and p.status in ('PENDING','ACTIVE') and p.validFrom < :validTo and p.validTo > :validFrom")
    boolean existsOverlapping(@Param("vehicleId") UUID vehicleId, @Param("zoneId") UUID zoneId,
                              @Param("validFrom") OffsetDateTime validFrom,
                              @Param("validTo") OffsetDateTime validTo);
}
