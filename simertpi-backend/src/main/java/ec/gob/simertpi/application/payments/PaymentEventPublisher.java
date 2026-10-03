package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.domain.payments.entity.Payment;

public interface PaymentEventPublisher {

    void publish(Payment payment, String eventType);
}
