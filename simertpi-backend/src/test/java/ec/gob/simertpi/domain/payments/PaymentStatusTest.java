package ec.gob.simertpi.domain.payments;

import ec.gob.simertpi.domain.payments.entity.PaymentStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentStatusTest {

    @Test
    void onlyAllowsDeclaredPaymentStateTransitions() {
        assertTrue(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PROCESSING));
        assertTrue(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.APPROVED));
        assertTrue(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.PENDING));
        assertTrue(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.DECLINED));
        assertTrue(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.CANCELLED));
        assertTrue(PaymentStatus.APPROVED.canTransitionTo(PaymentStatus.REFUNDED));
        assertFalse(PaymentStatus.APPROVED.canTransitionTo(PaymentStatus.DECLINED));
        assertFalse(PaymentStatus.APPROVED.canTransitionTo(PaymentStatus.FAILED));
        assertFalse(PaymentStatus.DECLINED.canTransitionTo(PaymentStatus.APPROVED));
        assertFalse(PaymentStatus.REFUNDED.canTransitionTo(PaymentStatus.APPROVED));
    }
}
