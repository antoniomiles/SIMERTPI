package ec.gob.simertpi.application.parking.rules;
import ec.gob.simertpi.domain.parking.entity.ParkingSession;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class SessionLifecycleTest {
    final Instant start=Instant.parse("2026-10-05T14:00:00Z");
    ParkingSession session(int minutes) {
        var s=new ParkingSession(); s.setStatus("ACTIVE"); s.setStartedAt(start.atOffset(ZoneOffset.UTC));
        s.setExpectedEndAt(start.plusSeconds(minutes*60L).atOffset(ZoneOffset.UTC)); return s;
    }
    SessionLifecycle.View project(ParkingSession s, long seconds, boolean warning) {
        return SessionLifecycle.project(s,240,10,warning,start.plusSeconds(seconds),600);
    }
    @Test void exactTemporalBoundariesAndActions() {
        var s=session(60);
        assertThat(project(s,0,false).state()).isEqualTo("ACTIVE");
        assertThat(project(s,3000,false).state()).isEqualTo("ENDING_SOON");
        for(long sec:new long[]{3600,4199}) {
            var v=project(s,sec,false); assertThat(v.state()).isEqualTo("EXPIRED_IN_GRACE");
            assertThat(v.closeAllowed()).isTrue(); assertThat(v.extensionEligible()).isTrue();
        }
        var v=project(s,4200,false); assertThat(v.state()).isEqualTo("GRACE_EXCEEDED");
        assertThat(v.closeAllowed()).isFalse(); assertThat(v.extensionEligible()).isFalse();
        v=project(s,4200,true); assertThat(v.state()).isEqualTo("REGULARIZATION_ALLOWED");
        assertThat(v.closeAllowed()).isTrue(); assertThat(v.extensionEligible()).isTrue();
        assertThat(s.getExpectedEndAt().toInstant()).isEqualTo(start.plusSeconds(3600));
    }
    @Test void maximumCannotBeResetByWarningButAllowsRecordedDeparture() {
        var s=session(240);
        assertThat(project(s,14400,false).state()).isEqualTo("EXPIRED_IN_GRACE");
        assertThat(project(s,14400,false).closeAllowed()).isTrue();
        assertThat(project(s,15000,false).state()).isEqualTo("GRACE_EXCEEDED");
        assertThat(project(s,15000,false).closeAllowed()).isFalse();
        assertThat(project(s,14400,true).extensionEligible()).isFalse();
        assertThat(project(s,15000,true).closeAllowed()).isTrue();
        assertThat(s.getStartedAt().toInstant()).isEqualTo(start);
    }
    @Test void elapsedGraceAndWarningNeverConsumePurchasedCapacity() {
        var s=session(180); s.setStatus("EXPIRED");
        var v=project(s,18000,true); // Five hours elapsed, only three purchased.
        assertThat(v.state()).isEqualTo("REGULARIZATION_ALLOWED");
        assertThat(v.extensionEligible()).isTrue(); assertThat(v.closeAllowed()).isTrue();
        assertThat(v.nextTransitionAt()).isNull();
        assertThat(Duration.between(s.getStartedAt(),s.getExpectedEndAt()).toMinutes()).isEqualTo(180);
        s.setStatus("MAX_TIME_REACHED"); // Legacy persisted status must not override actual accumulation.
        assertThat(project(s,18000,true).state()).isEqualTo("REGULARIZATION_ALLOWED");
    }
    @Test void extensionsAccumulateOnOriginalContractWithoutResettingStart() {
        var s=session(60);
        for (int total:new int[]{60,120,180,240}) {
            s.setExpectedEndAt(start.plusSeconds(total*60L).atOffset(ZoneOffset.UTC));
            assertThat(Duration.between(s.getStartedAt(),s.getExpectedEndAt()).toMinutes()).isEqualTo(total);
            var v=project(s,total*60L+600,true);
            assertThat(v.extensionEligible()).isEqualTo(total<240);
            assertThat(v.closeAllowed()).isTrue();
            assertThat(v.state()).isEqualTo(total<240?"REGULARIZATION_ALLOWED":"MAX_TIME_REACHED");
            assertThat(s.getStartedAt().toInstant()).isEqualTo(start);
        }
    }
    @Test void terminalOrMissingPolicyNeverUnlocks() {
        for(String status:new String[]{"COMPLETED","CANCELLED","PENDING_PAYMENT"}) {
            var s=session(60);s.setStatus(status); assertThat(project(s,4200,true).closeAllowed()).isFalse();
        }
        assertThat(SessionLifecycle.project(session(60),null,null,true,start,600).state()).isEqualTo("UNKNOWN");
    }
}
