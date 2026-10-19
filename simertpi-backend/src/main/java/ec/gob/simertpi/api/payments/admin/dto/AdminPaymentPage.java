package ec.gob.simertpi.api.payments.admin.dto;

import java.util.List;

public record AdminPaymentPage(
        List<AdminPaymentItem> items,
        List<AdminPaymentCurrencySummary> summaryByCurrency,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) { }
