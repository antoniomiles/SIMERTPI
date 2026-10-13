package ec.gob.simertpi.application.notifications;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
class ParkingPushDetailsTest {
    final OffsetDateTime start=OffsetDateTime.parse("2026-10-13T15:00:00Z");
    Map<String,Object> session() {
        return Map.of("started_at",start,"expected_end_at",start.plusMinutes(120),
            "initial_end",start.plusMinutes(60),"status","EXTENDED","plate","ABC-1234","code","PIN-DEV-001");
    }
    Map<String,String> source(String event) { return Map.of("eventType",event); }
    @Test void activationRetainsInitialContractAfterMultipleExtensionsAndUsesEcuadorDates() {
        var data=ParkingPushDetails.project(source("PARKING_STARTED"),session(),Map.of("payment_status","APPROVED"),start);
        assertThat(data).containsEntry("contractedMinutes","60").containsEntry("startedAt","13/10/2026 10:00")
            .containsEntry("expectedEndAt","13/10/2026 11:00").containsEntry("plate","ABC-1234").containsEntry("spaceCode","PIN-DEV-001");
    }
    @Test void eachExtensionUsesItsOwnApprovedPaymentAndEndInsteadOfCurrentSession() {
        for(int minutes:List.of(30,60)) {
            var operation=Map.<String,Object>of("payment_status","APPROVED","extension_status","APPROVED",
                "additional_minutes",minutes,"new_expected_end_at",start.plusMinutes(60+minutes),
                "amount",new BigDecimal(minutes==30?"0.50":"1.00"),"currency","USD");
            var data=ParkingPushDetails.project(source("PARKING_EXTENSION_CONFIRMED"),session(),operation,start);
            assertThat(data).containsEntry("additionalMinutes",Integer.toString(minutes))
                .containsEntry("amount",minutes==30?"0.50":"1.00").containsEntry("currency","USD")
                .containsEntry("expectedEndTime",minutes==30?"11:30":"12:00");
        }
    }
    @Test void pendingOrDeclinedPaymentDoesNotProduceSuccessDetails() {
        for(String status:List.of("PENDING","DECLINED","UNKNOWN"))
            assertThat(ParkingPushDetails.project(source("PARKING_EXTENSION_CONFIRMED"),session(),
                Map.of("payment_status",status,"extension_status","APPROVED"),start)).doesNotContainKeys("amount","additionalMinutes","plate");
    }
    @Test void reminderUsesRealEndAndExpiresExactlyAtThatEnd() {
        var now=start.plusMinutes(111);
        var data=ParkingPushDetails.project(source("PARKING_ENDING_SOON"),session(),Map.of(),now);
        assertThat(data).containsEntry("minutesRemaining","9")
            .containsEntry("validUntil",Long.toString(start.plusMinutes(120).toInstant().toEpochMilli()));
    }
    @Test void missingOrUnapprovedEventIsNotEligibleForPush() {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        UUID id=UUID.randomUUID(),owner=UUID.randomUUID();
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("FROM parking.parking_sessions"),
            org.mockito.ArgumentMatchers.eq(id),org.mockito.ArgumentMatchers.eq(owner))).thenReturn(List.of(session()));
        var data=new ParkingPushDetails(jdbc).enrich(source("PARKING_STARTED"),Map.of("reference_type","PARKING_SESSION",
            "reference_id",id,"user_id",owner,"outbox_event_id",UUID.randomUUID(),"notification_type","PARKING_STARTED"),start);
        assertThat(data).isEmpty();
    }
    @Test void extensionLookupIsBoundToOutboxPaymentNotLatestSessionExtension() {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        UUID owner=UUID.randomUUID(),sessionId=UUID.randomUUID(),event=UUID.randomUUID();
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("FROM parking.parking_sessions"),
            org.mockito.ArgumentMatchers.eq(sessionId),org.mockito.ArgumentMatchers.eq(owner))).thenReturn(List.of(session()));
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("WHERE o.id=?"),
            org.mockito.ArgumentMatchers.eq(event),org.mockito.ArgumentMatchers.eq("PARKING_EXTENSION_CONFIRMED"),org.mockito.ArgumentMatchers.eq(sessionId)))
            .thenReturn(List.of(Map.of("payment_status","APPROVED","extension_status","APPROVED","additional_minutes",30,
                "new_expected_end_at",start.plusMinutes(90),"amount",new BigDecimal("0.50"),"currency","USD")));
        var data=new ParkingPushDetails(jdbc).enrich(source("PARKING_EXTENSION_CONFIRMED"),Map.of("reference_type","PARKING_SESSION",
            "reference_id",sessionId,"user_id",owner,"outbox_event_id",event,"notification_type","PARKING_EXTENSION_CONFIRMED"),start);
        assertThat(data).containsEntry("expectedEndTime","11:30").containsEntry("amount","0.50");
    }
}
