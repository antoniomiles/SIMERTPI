package ec.gob.simertpi.simertpi_citizen_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPresentationPolicyTest {
    private val valid = mapOf(
        "notificationId" to "00000000-0000-4000-8000-000000000001",
        "eventType" to "PARKING_ENDING_SOON",
        "resourceType" to "PARKING_SESSION",
        "resourceId" to "00000000-0000-4000-8000-000000000002",
        "recipientOwnerId" to "citizen-a",
    )

    @Test fun acceptsOnlySupportedMinimalPayload() {
        assertTrue(PushPresentationPolicy.valid(valid))
        assertFalse(PushPresentationPolicy.valid(valid + ("eventType" to "PAYMENT_APPROVED")))
        assertFalse(PushPresentationPolicy.valid(valid - "resourceId"))
        assertFalse(PushPresentationPolicy.valid(valid + ("notificationId" to "not-a-uuid")))
    }

    @Test fun staleOwnerPayloadIsDroppedAfterAccountSwitchOrGateClear() {
        assertTrue(PushPresentationPolicy.allows(true, "citizen-a", valid))
        assertFalse(PushPresentationPolicy.allows(true, "citizen-b", valid))
        assertFalse(PushPresentationPolicy.allows(false, "citizen-a", valid))
        assertFalse(PushPresentationPolicy.allows(true, null, valid))
    }
}
