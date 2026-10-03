package ec.gob.simertpi.api.enforcement.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeViolationStatusRequest(
        @NotBlank(message = "Status is required")
        String status
) {}
