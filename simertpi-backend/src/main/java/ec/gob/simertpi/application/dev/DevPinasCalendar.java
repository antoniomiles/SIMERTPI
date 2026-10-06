package ec.gob.simertpi.application.dev;

import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Only the fixed exemptions expressly listed in art. 15 of the published 2021 text. */
final class DevPinasCalendar {
    private DevPinasCalendar() { }
    static void seed(JdbcTemplate jdbc, int year) {
        var zones=jdbc.queryForList("SELECT id FROM parking.zones WHERE code IN ('PIN-DEV-Z01','PIN-DEV-Z02','PIN-DEV-Z03')",UUID.class);
        for(int y=year-1;y<=year+2;y++) {
            var dates=List.of(LocalDate.of(y,1,1),LocalDate.of(y,11,8),LocalDate.of(y,11,9),
                    LocalDate.of(y,12,25),goodFriday(y));
            for(var zone:zones) for(var date:dates) {
                var id=UUID.nameUUIDFromBytes(("simertpi-dev-ordinance2021:"+zone+":"+date).getBytes(StandardCharsets.UTF_8));
                jdbc.update("""
                    INSERT INTO parking.holidays(id,holiday_date,name,zone_id,holiday_type,tariffed,normative_reference,valid_from,valid_to)
                    VALUES (?,?, 'DEV REFERENCIA 2021 / DIA NO TARIFADO',?, 'NON_TARIFFED',false,'Ordenanza publicada 2021 art. 15; vigencia por verificar',?,?)
                    ON CONFLICT DO NOTHING
                    """,id,date,zone,date,date);
            }
        }
    }
    static LocalDate goodFriday(int year) {
        // Gregorian Easter computation; no assumption about transferred national holidays.
        int a=year%19,b=year/100,c=year%100,d=b/4,e=b%4,f=(b+8)/25,g=(b-f+1)/3;
        int h=(19*a+b-d-g+15)%30,i=c/4,k=c%4,l=(32+2*e+2*i-h-k)%7,m=(a+11*h+22*l)/451;
        int n=h+l-7*m+114;
        return LocalDate.of(year,n/31,n%31+1).minusDays(2);
    }
}
