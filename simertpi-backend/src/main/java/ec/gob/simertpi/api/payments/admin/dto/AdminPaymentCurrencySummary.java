package ec.gob.simertpi.api.payments.admin.dto;

public record AdminPaymentCurrencySummary(
        String currency,
        long approvedCount,
        String approvedAmount,
        long pendingCount,
        String pendingAmount,
        long failedCount,
        String failedAmount,
        long refundedCount,
        String refundedAmount,
        long cancelledCount,
        String cancelledAmount
) { }
