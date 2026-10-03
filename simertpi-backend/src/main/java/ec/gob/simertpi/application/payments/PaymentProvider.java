package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.domain.payments.entity.Payment;
import ec.gob.simertpi.domain.payments.entity.PaymentAttempt;

/** Port for a future payment provider adapter. No external provider is wired in this checkpoint. */
public interface PaymentProvider {

    String providerCode();

    PaymentProviderResult initiate(Payment payment, PaymentAttempt attempt);
}
