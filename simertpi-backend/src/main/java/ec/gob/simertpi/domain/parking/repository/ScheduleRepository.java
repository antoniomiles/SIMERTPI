package ec.gob.simertpi.domain.parking.repository;

import ec.gob.simertpi.domain.parking.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    List<Schedule> findByZoneIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(
            UUID zoneId
    );

    List<Schedule> findByZoneIdAndDayOfWeekAndActiveTrue(
            UUID zoneId,
            Short dayOfWeek
    );
}
