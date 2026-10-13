package ec.gob.simertpi.application.notifications;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ProviderDeliveryEvidenceTest {
    @Test void dispatcherPreservesOnlyKnownTechnicalCodes() {
        for (String code : new String[] {"FCM_RESULT_INVALID", "FCM_TOKEN_INVALID", "FCM_TEMPORARY_FAILURE", "FCM_PERMANENT_FAILURE"})
            assertThat(ProviderDeliveryEvidence.safeError(code, "UNKNOWN")).isEqualTo(code);
        assertThat(ProviderDeliveryEvidence.safeError("private-provider-content", "UNKNOWN")).isEqualTo("UNKNOWN");
    }
    @Test void acceptanceIsNotDeviceReceipt() {
        assertThat(ProviderDeliveryEvidence.providerOutcome("DELIVERED")).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(ProviderDeliveryEvidence.providerOutcome("INVALID_DESTINATION")).isEqualTo("PROVIDER_REJECTED");
        assertThat(ProviderDeliveryEvidence.providerOutcome("UNKNOWN")).isEqualTo("UNCONFIRMED");
    }
    @Test void opaqueStorageDoesNotLoseAcceptedIdentifier() {
        assertThat(ProviderDeliveryEvidence.storageReference("0:123%abc+")).isEqualTo("0:123%abc+");
        assertThat(ProviderDeliveryEvidence.storageReference("x".repeat(200))).startsWith("sha256:");
    }
}
