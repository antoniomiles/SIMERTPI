package ec.gob.simertpi.application.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.sql.Timestamp;
import java.util.*;

/** Read-only event-specific push projection. Never changes payment or session state. */
final class ParkingPushDetails {
    private final JdbcTemplate jdbc;
    private static final ZoneId ECUADOR = ZoneId.of("America/Guayaquil");
    ParkingPushDetails(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    Map<String,String> enrich(Map<String,String> source, Map<String,Object> notification, OffsetDateTime now) {
        if (!"PARKING_SESSION".equals(notification.get("reference_type"))) return source;
        var rows = jdbc.queryForList("""
            SELECT s.started_at,s.expected_end_at,s.ended_at,s.status,v.plate,p.code,
              (SELECT min(e.previous_expected_end_at) FROM parking.session_extensions e
               WHERE e.parking_session_id=s.id AND e.status='APPROVED') AS initial_end
            FROM parking.parking_sessions s JOIN identity.vehicles v ON v.id=s.vehicle_id
            JOIN parking.parking_spaces p ON p.id=s.parking_space_id
            WHERE s.id=? AND s.user_id=?
            """, notification.get("reference_id"),notification.get("user_id"));
        if (rows.size()!=1) return Map.of();
        var session=rows.getFirst();
        Map<String,Object> operation=Map.of();
        String event=String.valueOf(notification.get("notification_type"));
        if (Set.of("PARKING_STARTED","PARKING_EXTENSION_CONFIRMED").contains(event)) {
            var payments=jdbc.queryForList("""
                SELECT pay.status AS payment_status,pay.amount,pay.currency,
                       e.additional_minutes,e.new_expected_end_at,e.status AS extension_status
                FROM audit.outbox_events o JOIN payments.payments pay ON pay.id=o.aggregate_id
                LEFT JOIN parking.session_extensions e ON e.payment_id=pay.id
                WHERE o.id=? AND o.aggregate_type='PAYMENT' AND o.event_type=?
                  AND pay.parking_session_id=?
                """,notification.get("outbox_event_id"),event,notification.get("reference_id"));
            if(payments.size()!=1 || !"APPROVED".equals(payments.getFirst().get("payment_status"))) return Map.of();
            operation=payments.getFirst();
            if("PARKING_EXTENSION_CONFIRMED".equals(event)&&!"APPROVED".equals(operation.get("extension_status"))) return Map.of();
        }
        var data=new LinkedHashMap<>(project(source,session,operation,now));
        var created=date(notification.get("created_at"));
        if(created!=null && !"PARKING_ENDING_SOON".equals(event) && !"PARKING_TIME_EXPIRED".equals(event))
            data.put("validUntil",Long.toString(created.plusHours(6).toInstant().toEpochMilli()));
        return Collections.unmodifiableMap(data);
    }
    static Map<String,String> project(Map<String,String> source,Map<String,Object> session,
                                      Map<String,Object> operation,OffsetDateTime now) {
        var data=new LinkedHashMap<>(source);
        String event=source.get("eventType");
        OffsetDateTime end=date(session.get("expected_end_at"));
        OffsetDateTime start=date(session.get("started_at"));
        if("PARKING_STARTED".equals(event)) {
            if(!"APPROVED".equals(operation.get("payment_status"))) return source;
            var initial=date(session.get("initial_end"));
            if(initial!=null) end=initial;
            if(start!=null&&end!=null) data.put("contractedMinutes",Long.toString(Duration.between(start,end).toMinutes()));
            if(start!=null) data.put("startedAt",formatted(start));
        } else if("PARKING_EXTENSION_CONFIRMED".equals(event)) {
            if(!"APPROVED".equals(operation.get("payment_status"))||!"APPROVED".equals(operation.get("extension_status"))) return source;
            end=date(operation.get("new_expected_end_at"));
            if(end==null) return source;
            data.put("additionalMinutes",String.valueOf(operation.get("additional_minutes")));
            data.put("amount",String.valueOf(operation.get("amount")));
            data.put("currency",String.valueOf(operation.get("currency")));
        } else if("PARKING_ENDING_SOON".equals(event)) {
            if(end==null || !end.isAfter(now) || !Set.of("ACTIVE","EXTENDED").contains(session.get("status"))) return source;
            data.put("minutesRemaining",Long.toString(Math.max(1,(Duration.between(now,end).toSeconds()+59)/60)));
        }
        if(session.get("plate")!=null) data.put("plate",session.get("plate").toString());
        if(session.get("code")!=null) data.put("spaceCode",session.get("code").toString());
        if("PARKING_COMPLETED".equals(event)) end=date(session.get("ended_at"));
        if(end!=null) {
            data.put("contractEndEpoch",Long.toString(end.toInstant().toEpochMilli()));
            data.put("expectedEndAt",formatted(end));
            data.put("expectedEndTime",end.atZoneSameInstant(ECUADOR).format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        Instant expiry=now.plusHours(6).toInstant();
        if("PARKING_ENDING_SOON".equals(event)) expiry=end.toInstant();
        else if("PARKING_TIME_EXPIRED".equals(event)&&end!=null) expiry=end.plusMinutes(30).toInstant();
        data.put("validUntil",Long.toString(expiry.toEpochMilli()));
        return Collections.unmodifiableMap(data);
    }
    private static String formatted(OffsetDateTime date) {
        return date.atZoneSameInstant(ECUADOR).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }
    private static OffsetDateTime date(Object value) {
        if(value instanceof OffsetDateTime d) return d;
        if(value instanceof Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC);
        return null;
    }
}
