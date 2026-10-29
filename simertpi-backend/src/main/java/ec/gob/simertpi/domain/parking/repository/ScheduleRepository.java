package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.UUID;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.id = :id")
    java.util.Optional<Schedule> findByIdForUpdate(@Param("id") UUID id);

    List<Schedule> findByZoneIdIsNullAndDayOfWeekAndActiveTrue(Short dayOfWeek);


    List<Schedule> findByZoneIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(
            UUID zoneId
    );

    List<Schedule> findByZoneIdAndDayOfWeekAndActiveTrue(
            UUID zoneId,
            Short dayOfWeek
    );
}
