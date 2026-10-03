package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.ParkingSpace;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
