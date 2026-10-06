package ec.gob.simertpi.application.parking.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** Presentation offers, all individually validated and priced by the existing rules engine. */
public final class ParkingDurationOptions {
    private ParkingDurationOptions() { }
    public static List<ParkingRulesResult> quotes(ParkingRulesResult policy, IntFunction<ParkingRulesResult> quote) {
        if (policy.minimumFractionMinutes() == null || policy.maximumContinuousMinutes() == null) return List.of();
        int step = policy.minimumFractionMinutes(), max = policy.maximumContinuousMinutes();
        if (step <= 0 || max < step) return List.of();
        var result = new ArrayList<ParkingRulesResult>();
        // Bound the response even if an administrative configuration is accidentally excessive.
        if ((long) max / step > 288) throw new IllegalArgumentException("Too many configured duration options");
        for (long minutes = step; minutes <= max; minutes += step) {
            var value = quote.apply((int) minutes);
            if (value.operational() && value.calculatedAmount() != null) result.add(value);
        }
        return List.copyOf(result);
    }
}
