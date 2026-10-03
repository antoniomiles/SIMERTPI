package ec.gob.simertpi.domain.payments.entity;

import java.util.EnumSet;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    APPROVED,
    DECLINED,
    FAILED,
    REFUNDED,
    CANCELLED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING -> EnumSet.of(PROCESSING, APPROVED, DECLINED, FAILED, CANCELLED).contains(next);
            case PROCESSING -> EnumSet.of(APPROVED, DECLINED, FAILED, CANCELLED).contains(next);
            case APPROVED -> next == REFUNDED;
            case DECLINED, FAILED, REFUNDED, CANCELLED -> false;
        };
    }

    public static PaymentStatus fromCode(String value) {
        return PaymentStatus.valueOf(value);
    }
}
