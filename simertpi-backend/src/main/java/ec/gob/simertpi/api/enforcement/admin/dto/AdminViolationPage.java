package ec.gob.simertpi.api.enforcement.admin.dto;

import java.util.List;

public record AdminViolationPage(
        List<AdminViolationItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) { }
