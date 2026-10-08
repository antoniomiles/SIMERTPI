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

    @Test fun rendersEventSpecificPrivacySafeCopy() {
        val started = PushEventCopy.forEvent("PARKING_STARTED")!!
        assertTrue(started.first.contains("iniciado"))
        assertTrue(started.second.contains("activo"))
        val ending = PushEventCopy.forEvent("PARKING_ENDING_SOON")!!
        assertTrue(ending.first.contains("vencer"))
        val expired = PushEventCopy.forEvent("PARKING_TIME_EXPIRED")!!
        assertTrue(expired.second.contains("contratado"))
        val extension = PushEventCopy.forEvent("PARKING_EXTENSION_CONFIRMED")!!
        assertTrue(extension.first.contains("confirmada"))
        val completed = PushEventCopy.forEvent("PARKING_COMPLETED")!!
        assertTrue(completed.first.contains("finalizado"))
        assertFalse(started.second.contains("USD"))
        assertFalse(started.second.contains("PIN-"))
        assertFalse(started.second.contains("1234"))
        assertFalse(PushEventCopy.forEvent("UNKNOWN_EVENT") != null)
    }

    @Test fun staleOwnerPayloadIsDroppedAfterAccountSwitchOrGateClear() {
        assertTrue(PushPresentationPolicy.allows(true, "citizen-a", valid))
        assertFalse(PushPresentationPolicy.allows(true, "citizen-b", valid))
        assertFalse(PushPresentationPolicy.allows(false, "citizen-a", valid))
        assertFalse(PushPresentationPolicy.allows(true, null, valid))
    }

    @Test fun coldStartRestoresOnlyExplicitlyConsentedBackendRegisteredOwner() {
        val restored = PushAuthorization(
            ownerId = "citizen-a",
            backendDeviceId = "00000000-0000-4000-8000-000000000003",
            consentGranted = true,
            backendRegistrationConfirmed = true,
            enabled = true,
        )
        assertTrue(PushPresentationPolicy.canRestore(restored, notificationsAllowed = true))
        assertTrue(PushPresentationPolicy.allows(true, restored.ownerId, valid))
        assertFalse(PushPresentationPolicy.allows(true, "citizen-b", valid))
    }

    @Test fun coldStartDeniesMissingCorruptOrIncompleteAuthorization() {
        val complete = PushAuthorization(
            ownerId = "citizen-a",
            backendDeviceId = "00000000-0000-4000-8000-000000000003",
            consentGranted = true,
            backendRegistrationConfirmed = true,
            enabled = true,
        )
        assertFalse(PushPresentationPolicy.canRestore(complete.copy(consentGranted = false), true))
        assertFalse(PushPresentationPolicy.canRestore(complete.copy(backendRegistrationConfirmed = false), true))
        assertFalse(PushPresentationPolicy.canRestore(complete.copy(ownerId = null), true))
        assertFalse(PushPresentationPolicy.canRestore(complete.copy(backendDeviceId = null), true))
        assertFalse(PushPresentationPolicy.canRestore(complete.copy(backendDeviceId = "corrupt"), true))
        assertFalse(PushPresentationPolicy.canRestore(complete.copy(enabled = false), true))
        assertFalse(PushPresentationPolicy.canRestore(complete.copy(consentGranted = false), true))
        assertFalse(PushPresentationPolicy.canRestore(complete, notificationsAllowed = false))
    }

    @Test fun restartDoesNotReauthorizeDeniedPermissionOrUnknownEvent() {
        val restored = PushAuthorization(
            ownerId = "citizen-a",
            backendDeviceId = "00000000-0000-4000-8000-000000000003",
            consentGranted = true,
            backendRegistrationConfirmed = true,
            enabled = true,
        )
        assertTrue(PushPresentationPolicy.canRestore(restored, notificationsAllowed = true))
        assertFalse(PushPresentationPolicy.allows(true, "citizen-a", valid + ("eventType" to "UNKNOWN")))
        assertFalse(PushPresentationPolicy.allows(false, "citizen-a", valid))
    }
}
