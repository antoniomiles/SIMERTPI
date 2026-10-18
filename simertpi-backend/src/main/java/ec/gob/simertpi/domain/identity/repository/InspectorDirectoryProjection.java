package ec.gob.simertpi.domain.identity.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface InspectorDirectoryProjection {
    UUID getId();
    String getUsername();
    String getEmail();
    String getFirstName();
    String getLastName();
    String getPhone();
    boolean getEnabled();
    boolean getActivationPending();
    OffsetDateTime getCreatedAt();
}
