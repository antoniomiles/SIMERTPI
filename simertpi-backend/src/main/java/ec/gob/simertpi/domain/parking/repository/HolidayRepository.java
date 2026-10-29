package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Holiday h where h.id = :id")
    Optional<Holiday> findByIdForUpdate(@Param("id") UUID id);

    Optional<Holiday> findByHolidayDateAndActiveTrue(LocalDate holidayDate);

    List<Holiday> findByHolidayDateAndZoneIdAndActiveTrue(
            LocalDate holidayDate,
            UUID zoneId
    );

    List<Holiday> findByHolidayDateAndZoneIdIsNullAndActiveTrue(
            LocalDate holidayDate
    );
}
