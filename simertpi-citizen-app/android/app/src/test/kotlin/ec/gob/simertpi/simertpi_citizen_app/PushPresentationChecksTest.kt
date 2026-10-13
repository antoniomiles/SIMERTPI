package ec.gob.simertpi.simertpi_citizen_app
import org.junit.Assert.*
import org.junit.Test
class PushPresentationChecksTest {
    @Test fun receivesButDoesNotPresentWhenChannelBlocked() {
        assertEquals("CHANNEL_BLOCKED", PushPresentationChecks.rejection(true, false, true, true, false, true))
    }
    @Test fun diagnosesActualPermissionAndAccountIsolation() {
        assertEquals("PERMISSION_BLOCKED", PushPresentationChecks.rejection(false, true, true, true, false, true))
        assertEquals("ACCOUNT_GATE_BLOCKED", PushPresentationChecks.rejection(true, true, false, true, false, true))
    }
    @Test fun secondRouteCannotPresentDuplicateOrExpiredContract() {
        assertEquals("DUPLICATE", PushPresentationChecks.rejection(true, true, true, true, true, true))
        assertEquals("EXPIRED", PushPresentationChecks.rejection(true, true, true, false, false, true))
        assertEquals("STALE_CONTRACT", PushPresentationChecks.rejection(true, true, true, true, false, false))
    }
    @Test fun eligibleNotificationCanRequestPresentation() {
        assertNull(PushPresentationChecks.rejection(true, true, true, true, false, true))
    }
    @Test fun revalidationRetainsOnlySameConfirmedIdentity() {
        val a=PushAuthorization("citizen-a","00000000-0000-4000-8000-000000000002",true,true,true)
        assertTrue(PushPresentationPolicy.canRetain(a,a,true))
        assertFalse(PushPresentationPolicy.canRetain(a,a.copy(ownerId="citizen-b"),true))
        assertFalse(PushPresentationPolicy.canRetain(a,a.copy(enabled=false),true))
        assertFalse(PushPresentationPolicy.canRetain(a,a,false))
        assertFalse(PushPresentationPolicy.canRetain(a,a.copy(backendDeviceId="00000000-0000-4000-8000-000000000003"),true))
    }
    @Test fun diagnosticsAcceptOnlyFixedTechnicalVocabulary() {
        assertTrue(PushDiagnosticVocabulary.accepts("NATIVE_SERVICE","PRESENTATION_REQUESTED"))
        assertTrue(PushDiagnosticVocabulary.accepts("FLUTTER_RECEIVER","DUPLICATE"))
        assertFalse(PushDiagnosticVocabulary.accepts("private-token","RECEIVED"))
        assertFalse(PushDiagnosticVocabulary.accepts("NATIVE_SERVICE","private-vehicle-content"))
    }
}
