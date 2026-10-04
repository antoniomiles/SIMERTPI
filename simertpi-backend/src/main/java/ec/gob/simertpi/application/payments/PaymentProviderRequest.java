package ec.gob.simertpi.application.payments;
import java.math.BigDecimal;
import java.util.UUID;
public record PaymentProviderRequest(UUID paymentId, UUID sessionId, BigDecimal amount,
 String currency, String reference, UUID idempotencyKey, String correlationId) { }
