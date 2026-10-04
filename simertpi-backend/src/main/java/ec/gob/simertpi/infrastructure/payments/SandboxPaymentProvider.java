package ec.gob.simertpi.infrastructure.payments;
import ec.gob.simertpi.application.payments.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
@Profile({"dev","test"})
@ConditionalOnProperty(name="simertpi.payments.sandbox.enabled",havingValue="true")
public class SandboxPaymentProvider implements PaymentProvider {
 private final Environment env;
 public SandboxPaymentProvider(Environment env) {
  this.env=env;
  if(env.getActiveProfiles().length==0 || Arrays.stream(env.getActiveProfiles()).anyMatch(p -> !Set.of("dev","test").contains(p)))
   throw new IllegalStateException("Sandbox requires exclusively dev/test profiles");
 }
 public String providerCode() {return "SANDBOX_STUB";}
 private PaymentProviderResult result(PaymentProviderRequest request) {
  var status=ProviderPaymentStatus.valueOf(env.getProperty("simertpi.payments.sandbox.outcome","PENDING"));
  return new PaymentProviderResult(status,"sandbox-"+request.idempotencyKey(),"sandbox-"+request.paymentId());
 }
 public PaymentProviderResult createPayment(PaymentProviderRequest request) {return result(request);}
 public PaymentProviderResult queryPayment(String id,PaymentProviderRequest request) {return result(request);}
 public PaymentProviderResult cancelPayment(String id,PaymentProviderRequest request) {return new PaymentProviderResult(ProviderPaymentStatus.CANCELLED,id,null);}
}
