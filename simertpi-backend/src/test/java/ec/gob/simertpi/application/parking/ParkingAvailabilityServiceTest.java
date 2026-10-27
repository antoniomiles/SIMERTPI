package ec.gob.simertpi.application.parking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ParkingAvailabilityServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-05T12:00:00Z");
    @ParameterizedTest @ValueSource(strings={"ACTIVE","EXTENDED","EXPIRED","MAX_TIME_REACHED","PENDING_PAYMENT"})
    void operationalStatesOccupy(String status) {
        var a=project(status,true,1800);
        assertThat(a.operationalStatus()).isEqualTo("OCCUPIED");
        assertThat(a.selectable()).isFalse();
        if(status.equals("PENDING_PAYMENT")) { assertThat(a.expectedEndAt()).isNull(); assertThat(a.remainingSeconds()).isNull(); }
        else assertThat(a.remainingSeconds()).isEqualTo(1800L);
    }
    @Test void thresholdIsProjectionAndExpiredNeverPromisesRelease() {
        assertThat(project("ACTIVE",true,600).operationalStatus()).isEqualTo("ENDING_SOON");
        assertThat(project("EXTENDED",true,601).operationalStatus()).isEqualTo("OCCUPIED");
        assertThat(project("EXPIRED",true,-5).remainingSeconds()).isZero();
        assertThat(project("EXPIRED",true,-5).operationalStatus()).isEqualTo("OCCUPIED");
        assertThat(project(null,true,0).selectable()).isTrue();
        assertThat(project(null,false,0).operationalStatus()).isEqualTo("DISABLED");
        assertThat(project("ACTIVE",false,5).selectable()).isFalse();
        assertThat(java.util.Arrays.stream(ParkingAvailabilityService.Availability.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("userId","vehicleId","sessionId","paymentId","plate");
    }
    @Test void mapProjectionUsesBackendTimeAndConfiguredThresholdWithoutCallingExpiredSpacesFree() {
        assertThat(map("ACTIVE",NOW.plusSeconds(601),true)).isEqualTo("ACTIVE");
        assertThat(map("ACTIVE",NOW.plusSeconds(600),true)).isEqualTo("ENDING_SOON");
        assertThat(map("EXTENDED",NOW.minusSeconds(1),true)).isEqualTo("EXPIRED");
        assertThat(map("EXPIRED",NOW.minusSeconds(30),true)).isEqualTo("EXPIRED");
        assertThat(map("MAX_TIME_REACHED",NOW.minusSeconds(30),true)).isEqualTo("MAX_TIME_REACHED");
        assertThat(map("EXPIRED",NOW.plusSeconds(30),true)).isEqualTo("UNKNOWN");
        assertThat(map("MAX_TIME_REACHED",NOW.plusSeconds(30),true)).isEqualTo("UNKNOWN");
        assertThat(map("PENDING_PAYMENT",NOW.plusSeconds(30),true)).isEqualTo("PAYMENT_PENDING");
        assertThat(map("PENDING_PAYMENT",NOW.minusSeconds(30),true)).isEqualTo("PAYMENT_PENDING");
        assertThat(map(null,null,true)).isEqualTo("NO_ACTIVE_SESSION");
        assertThat(map("ACTIVE",NOW.plusSeconds(60),false)).isEqualTo("DISABLED");
        assertThat(map("PENDING_PAYMENT",NOW.minusSeconds(30),false)).isEqualTo("DISABLED");
        assertThat(map("ACTIVE",null,true)).isEqualTo("UNKNOWN");
    }
    private static String map(String state,Instant end,boolean enabled) {
        return ParkingAvailabilityService.mapStatus(enabled,state,end==null?null:end.atOffset(ZoneOffset.UTC),NOW,600);
    }
    private static ParkingAvailabilityService.Availability project(String state,boolean enabled,long seconds) {
        return ParkingAvailabilityService.project(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"FIXTURE",enabled,enabled,
                state,NOW.plusSeconds(seconds).atOffset(ZoneOffset.ofHours(-5)),null,null,NOW,600);
    }
}
