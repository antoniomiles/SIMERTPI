package ec.gob.simertpi.application.identity.invitation;

import java.time.OffsetDateTime;
import java.util.List;

/** Sends a one-time account activation invitation without returning or logging its token. */
public interface InvitationEmailSender {
    /** Non-secret name of the active transport, suitable for status responses. */
    default String providerCode() { return "UNCONFIGURED"; }

    /** True only when the transport has all required non-secret configuration. */
    default boolean configured() { return false; }

    DeliveryResult send(String destination, String displayName, String username, List<String> roleNames,
                        String activationUrl, OffsetDateTime expiresAt);

    enum Status {
        ACCEPTED_BY_PROVIDER,
        UNCONFIGURED,
        FAILED
    }

    record DeliveryResult(Status status) {
        public static DeliveryResult acceptedByProvider() {
            return new DeliveryResult(Status.ACCEPTED_BY_PROVIDER);
        }

        public static DeliveryResult unconfigured() {
            return new DeliveryResult(Status.UNCONFIGURED);
        }

        public static DeliveryResult failed() {
            return new DeliveryResult(Status.FAILED);
        }
    }
}
