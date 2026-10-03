package ec.gob.simertpi.application.payments.webhook;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.application.payments.PaymentService;
import ec.gob.simertpi.domain.payments.webhook.entity.PaymentWebhook;
import ec.gob.simertpi.domain.payments.webhook.repository.PaymentWebhookRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentWebhookServiceTest {

    private final PaymentWebhookRepository repository = mock(PaymentWebhookRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);

    @Test
    void doesNotPersistOrApplyAnUnverifiedWebhook() {
        PaymentWebhookService service = new PaymentWebhookService(repository, paymentService,
                (provider, eventId, timestamp, signature, payload) -> false);

        assertThrows(ForbiddenException.class, () -> service.registerEvent("FAKE", "evt-1",
                "PAYMENT_APPROVED", "txn-1", UUID.randomUUID(), "{}", "now", "bad"));
        verify(repository, never()).save(any(PaymentWebhook.class));
        verifyNoInteractions(paymentService);
    }

    @Test
    void duplicateProviderEventReturnsStoredEventWithoutApplyingItTwice() {
        PaymentWebhook original = new PaymentWebhook();
        original.setId(UUID.randomUUID());
        original.setProvider("FAKE");
        original.setProviderEventId("evt-duplicate");
        original.setProcessed(true);
        when(repository.findByProviderAndProviderEventId("FAKE", "evt-duplicate"))
                .thenReturn(Optional.of(original));
        PaymentWebhookService service = new PaymentWebhookService(repository, paymentService,
                (provider, eventId, timestamp, signature, payload) -> true);

        service.registerEvent("FAKE", "evt-duplicate", "PAYMENT_APPROVED", "txn-1",
                UUID.randomUUID(), "{}", "now", "verified-by-test");

        verify(repository, never()).save(any(PaymentWebhook.class));
        verifyNoInteractions(paymentService);
    }
}
