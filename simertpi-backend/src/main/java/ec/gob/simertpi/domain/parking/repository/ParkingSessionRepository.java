package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParkingSessionRepository extends JpaRepository<ParkingSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ParkingSession s where s.id = :id")
    Optional<ParkingSession> findByIdForUpdate(@Param("id") UUID id);

    List<ParkingSession> findByUserId(UUID userId);

    List<ParkingSession> findByVehicleId(UUID vehicleId);

    boolean existsByVehicleIdAndStatusIn(UUID vehicleId, List<String> statuses);

    Optional<ParkingSession> findFirstByParkingSpaceIdAndStatusInOrderByStartedAtDesc(
            UUID parkingSpaceId,
            List<String> statuses
    );

    boolean existsByParkingSpaceIdAndStatusIn(
            UUID parkingSpaceId,
            List<String> statuses
    );

    List<ParkingSession> findByStatusIn(List<String> statuses);

    long countByParkingSpaceId(UUID parkingSpaceId);
}
