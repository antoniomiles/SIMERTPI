package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    Optional<Holiday> findByHolidayDateAndActiveTrue(LocalDate holidayDate);

    List<Holiday> findByHolidayDateAndZoneIdAndActiveTrue(
            LocalDate holidayDate,
            UUID zoneId
    );

    List<Holiday> findByHolidayDateAndZoneIdIsNullAndActiveTrue(
            LocalDate holidayDate
    );
}
