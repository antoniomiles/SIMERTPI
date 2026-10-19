package ec.gob.simertpi.api.parking.dto;

import java.util.List;

public record AdminParkingSessionPage(List<AdminParkingSessionView> items, int page, int size,
        long totalElements, int totalPages, boolean first, boolean last) { }
