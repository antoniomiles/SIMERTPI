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

    @Test fun lateOldReminderCannotOverrideReceivedExtension() {
        assertFalse(PushDeliveryPolicy.currentContract(valid + ("contractEndEpoch" to "1000"),2000))
        assertTrue(PushDeliveryPolicy.currentContract(valid + ("contractEndEpoch" to "2000"),2000))
        assertTrue(PushDeliveryPolicy.currentContract(valid + mapOf("eventType" to "PARKING_EXTENSION_CONFIRMED", "contractEndEpoch" to "1000"),2000))
    }
    @Test fun expiresLatePushAndRejectsMalformedExpiry() {
        assertTrue(PushDeliveryPolicy.fresh(valid + ("validUntil" to "2000"), 1000))
        assertFalse(PushDeliveryPolicy.fresh(valid + ("validUntil" to "1000"), 1000))
        assertFalse(PushDeliveryPolicy.fresh(valid + ("validUntil" to "invalid"), 1000))
    }
    @Test fun detailedCopyUsesConfirmedOperationAndEcuadorTimes() {
        val data=valid + mapOf("eventType" to "PARKING_EXTENSION_CONFIRMED", "plate" to "ABC-1234",
            "additionalMinutes" to "30", "amount" to "0.50", "currency" to "USD",
            "expectedEndAt" to "13/10/2026 11:30", "expectedEndTime" to "11:30")
        val copy=PushEventCopy.forEvent("PARKING_EXTENSION_CONFIRMED",data)!!.second
        assertTrue(copy.contains("ABC-1234")); assertTrue(copy.contains("0.50 USD")); assertTrue(copy.contains("13/10/2026 11:30"))
        val activation=PushEventCopy.forEvent("PARKING_STARTED",data + mapOf("eventType" to "PARKING_STARTED",
            "spaceCode" to "PIN-DEV-001","startedAt" to "13/10/2026 10:00"))!!.second
        assertTrue(activation.contains("PIN-DEV-001")); assertTrue(activation.contains("Inicio: 13/10/2026 10:00"))
    }
    @Test fun acceptsOnlySupportedMinimalPayload() {
        assertTrue(PushPresentationPolicy.valid(valid))
        assertFalse(PushPresentationPolicy.valid(valid + ("eventType" to "PAYMENT_APPROVED")))
        assertFalse(PushPresentationPolicy.valid(valid - "resourceId"))
        assertFalse(PushPresentationPolicy.valid(valid + ("notificationId" to "not-a-uuid")))
    }

    @Test fun rendersEventSpecificPrivacySafeCopy() {
        val started = PushEventCopy.forEvent("PARKING_STARTED", mapOf("contractedMinutes" to "60", "expectedEndTime" to "11:00"))!!
        assertTrue(started.first.contains("iniciado"))
        assertTrue(started.second.contains("60 minutos"))
        assertTrue(started.second.contains("11:00"))
        val ending = PushEventCopy.forEvent("PARKING_ENDING_SOON")!!
        assertTrue(ending.first.contains("vencer"))
        val detailedEnding = PushEventCopy.forEvent("PARKING_ENDING_SOON", mapOf("minutesRemaining" to "9"))!!
        assertTrue(detailedEnding.second.contains("9 minutos"))
        val expired = PushEventCopy.forEvent("PARKING_TIME_EXPIRED", mapOf("expectedEndTime" to "11:00"))!!
        assertTrue(expired.second.contains("11:00"))
        assertTrue(expired.second.contains("contratado"))
        val extension = PushEventCopy.forEvent("PARKING_EXTENSION_CONFIRMED", mapOf("additionalMinutes" to "30", "expectedEndTime" to "11:30"))!!
        assertTrue(extension.first.contains("confirmada"))
        assertTrue(extension.second.contains("30 minutos"))
        assertTrue(extension.second.contains("11:30"))
        val completed = PushEventCopy.forEvent("PARKING_COMPLETED")!!
        assertTrue(completed.first.contains("finalizado"))
        assertFalse(started.second.contains("USD"))
        assertFalse(started.second.contains("PIN-"))
        assertFalse(started.second.contains("1234"))
        assertFalse(PushEventCopy.forEvent("UNKNOWN_EVENT") != null)
    }

    @Test fun pushCopyRejectsUntrustedDisplayFieldsAndFallsBackSafely() {
        val started = PushEventCopy.forEvent("PARKING_STARTED", mapOf("contractedMinutes" to "999", "expectedEndTime" to "DROP TABLE"))!!
        assertTrue(started.second.contains("se inició correctamente"))
        assertFalse(started.second.contains("999"))
        val completed = PushEventCopy.forEvent("PARKING_COMPLETED", mapOf("endedAt" to "23:90"))!!
        assertTrue(completed.second.contains("ha finalizado"))
        assertFalse(completed.second.contains("23:90"))
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
