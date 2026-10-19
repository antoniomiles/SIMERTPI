package ec.gob.simertpi.api.reports.admin.dto;

import java.time.LocalDate;
import java.util.List;

public record AdminReportResponse(
        LocalDate from,
        LocalDate to,
        String timeZone,
        Operational operational,
        Financial financial) {
    public record Operational(
            long sessionsStarted,
            List<CountByCode> sessionsByStatus,
            List<ZoneSessionCount> sessionsByZone,
            List<DailySessionCount> dailySessions,
            RecordedDuration recordedDuration,
            List<ZoneSpaceCount> spacesByZone) { }

    public record Financial(
            List<PaymentStatusAmount> paymentsByCurrentStatus,
            List<CurrencyAmountCount> approvedPaymentsByPaidAt,
            List<DailyApprovedPayment> dailyApprovedPayments,
            List<ZoneApprovedPayment> approvedPaymentsByZone) { }

    public record CountByCode(String code, long count) { }
    public record ZoneSessionCount(String zoneCode, String zoneName, long count) { }
    public record DailySessionCount(LocalDate date, long count) { }
    public record RecordedDuration(long endedSessionCount, String totalSeconds, String averageSeconds) { }
    public record ZoneSpaceCount(String zoneCode, String zoneName, long configuredSpaces, long activeSpaces) { }
    public record PaymentStatusAmount(String status, String currency, long count, String amount) { }
    public record CurrencyAmountCount(String currency, long count, String amount) { }
    public record DailyApprovedPayment(LocalDate date, String currency, long count, String amount) { }
    public record ZoneApprovedPayment(String zoneCode, String zoneName, String currency, long count, String amount) { }
}
