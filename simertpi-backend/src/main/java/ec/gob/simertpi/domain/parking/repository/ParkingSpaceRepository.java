package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParkingSpaceRepository extends JpaRepository<ParkingSpace, UUID> {

    Optional<ParkingSpace> findByCode(String code);

    Optional<ParkingSpace> findByQrCode(String qrCode);

    List<ParkingSpace> findByStreetId(UUID streetId);

    boolean existsByCode(String code);

    boolean existsByQrCode(String qrCode);

    boolean existsByStreetIdAndSpaceNumber(UUID streetId, String spaceNumber);

    boolean existsByCodeAndIdNot(String code, UUID id);
    boolean existsByQrCodeAndIdNot(String qrCode, UUID id);
    boolean existsByStreetIdAndSpaceNumberAndIdNot(UUID streetId, String spaceNumber, UUID id);
    Optional<ParkingSpace> findFirstByLatitudeAndLongitudeAndIdNot(BigDecimal latitude, BigDecimal longitude, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ParkingSpace p where p.id = :id")
    Optional<ParkingSpace> findByIdForUpdate(@Param("id") UUID id);

}
