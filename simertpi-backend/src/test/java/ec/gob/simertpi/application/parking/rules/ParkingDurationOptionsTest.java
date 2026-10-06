package ec.gob.simertpi.application.parking.rules;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ParkingDurationOptionsTest {
    private ParkingRulesResult result(int min, int max, Integer duration, boolean valid) {
        return new ParkingRulesResult(null,null,null,true,valid,false,null,"FIXTURE",min,max,10,
                "USD",new BigDecimal("0.25"),60,duration==null?null:new BigDecimal("0.13"),duration,
                duration==null?null:duration.longValue(),null,true,null,null);
    }
    @Test void usesEngineForEveryOptionAndOmitsRejectedDurations() {
        var called = new java.util.ArrayList<Integer>();
        var options = ParkingDurationOptions.quotes(result(30,240,null,true), n -> {
            called.add(n); return result(30,240,n,n<=120);
        });
        assertThat(called).containsExactly(30,60,90,120,150,180,210,240);
        assertThat(options).extracting(ParkingRulesResult::requestedDurationMinutes).containsExactly(30,60,90,120);
        assertThat(options).allSatisfy(q -> assertThat(q.calculatedAmount()).isEqualByComparingTo("0.13"));
    }
    @Test void responseIsBoundedAndLargeIntegersDoNotOverflow() {
        assertThatThrownBy(() -> ParkingDurationOptions.quotes(result(1,289,null,true), n -> result(1,289,n,true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ParkingDurationOptions.quotes(result(Integer.MAX_VALUE,Integer.MAX_VALUE,null,true),
                n -> result(n,n,n,true))).hasSize(1);
        assertThat(ParkingDurationOptions.quotes(result(0,240,null,true), n -> {throw new AssertionError();})).isEmpty();
    }
}
