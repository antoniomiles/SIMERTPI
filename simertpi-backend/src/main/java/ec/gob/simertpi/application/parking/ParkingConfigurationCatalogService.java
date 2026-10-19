package ec.gob.simertpi.application.parking;

import ec.gob.simertpi.domain.parking.entity.Holiday;
import ec.gob.simertpi.domain.parking.entity.Schedule;
import ec.gob.simertpi.domain.parking.entity.Tariff;
import ec.gob.simertpi.domain.parking.entity.Zone;
import ec.gob.simertpi.domain.parking.repository.HolidayRepository;
import ec.gob.simertpi.domain.parking.repository.ScheduleRepository;
import ec.gob.simertpi.domain.parking.repository.TariffRepository;
import ec.gob.simertpi.domain.parking.repository.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ParkingConfigurationCatalogService {
    private final TariffRepository tariffs;
    private final ScheduleRepository schedules;
    private final HolidayRepository holidays;
    private final ZoneRepository zones;

    public ParkingConfigurationCatalogService(TariffRepository tariffs, ScheduleRepository schedules,
            HolidayRepository holidays, ZoneRepository zones) {
        this.tariffs = tariffs;
        this.schedules = schedules;
        this.holidays = holidays;
        this.zones = zones;
    }

    public List<Tariff> findTariffs() { return tariffs.findAll(); }
    public List<Schedule> findSchedules() { return schedules.findAll(); }
    public List<Holiday> findCalendarExceptions() { return holidays.findAll(); }
    public List<Zone> findZones() { return zones.findAll(); }
}
