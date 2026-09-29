package ec.gob.simertpi.api.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String username,
    String email,
    String firstName,
    String lastName,
    String phone,
    boolean enabled,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}