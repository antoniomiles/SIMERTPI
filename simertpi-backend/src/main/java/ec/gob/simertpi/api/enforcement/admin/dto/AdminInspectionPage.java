package ec.gob.simertpi.api.enforcement.admin.dto;

import java.util.List;

public record AdminInspectionPage(
        List<AdminInspectionItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) { }
