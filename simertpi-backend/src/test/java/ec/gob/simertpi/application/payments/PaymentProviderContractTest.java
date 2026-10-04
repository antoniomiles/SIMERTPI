package ec.gob.simertpi.application.payments;
import ec.gob.simertpi.infrastructure.payments.SandboxPaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class PaymentProviderContractTest {
 @Test void unconfiguredProviderFailsClosed() {assertThrows(PaymentProviderUnavailableException.class,()->new PaymentProviderRegistry(List.of(),new MockEnvironment()).selected());}
 @Test void unknownProviderFailsClosed() {assertThrows(PaymentProviderUnavailableException.class,()->new PaymentProviderRegistry(List.of(),new MockEnvironment().withProperty("simertpi.payments.provider","BANCO_PICHINCHA")).selected());}
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.EnumSource(value=ProviderPaymentStatus.class,names={"APPROVED","DECLINED","PENDING","FAILED"})
 void sandboxOutcomesAreDeterministic(ProviderPaymentStatus status) {
  var env=new MockEnvironment().withProperty("simertpi.payments.sandbox.outcome",status.name());env.setActiveProfiles("test");
  var sandbox=new SandboxPaymentProvider(env);var request=new PaymentProviderRequest(UUID.randomUUID(),UUID.randomUUID(),BigDecimal.ONE,"USD","test",UUID.randomUUID(),null);
  assertEquals(status,sandbox.createPayment(request).status());assertEquals(sandbox.createPayment(request),sandbox.queryPayment(null,request));
  assertEquals(ProviderPaymentStatus.CANCELLED,sandbox.cancelPayment("test-only",request).status());
 }
 @Test void sandboxBeanIsAbsentInProductionEvenIfFlagIsTrue() {
  try(var context=new AnnotationConfigApplicationContext()) {
   context.getEnvironment().setActiveProfiles("prod");context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("simertpi.payments.sandbox.enabled","true")));
   context.register(SandboxPaymentProvider.class);context.refresh();assertTrue(context.getBeansOfType(PaymentProvider.class).isEmpty());
  }
 }
 @Test void mixedProductionAndDevProfilesFailClosed() {
  var env=new MockEnvironment();env.setActiveProfiles("dev","prod");assertThrows(IllegalStateException.class,()->new SandboxPaymentProvider(env));
 }
 @Test void sandboxRequiresExplicitEnableFlag() {
  try(var context=new AnnotationConfigApplicationContext()) {context.getEnvironment().setActiveProfiles("dev");context.register(SandboxPaymentProvider.class);context.refresh();assertTrue(context.getBeansOfType(PaymentProvider.class).isEmpty());}
 }
 @Test void futureHttpAdapterRequiresExplicitTimeouts() {
  assertThrows(PaymentProviderUnavailableException.class,()->new PaymentTransportSettings(new MockEnvironment()).connectTimeout());
  var settings=new PaymentTransportSettings(new MockEnvironment().withProperty("simertpi.payments.connect-timeout","PT3S").withProperty("simertpi.payments.read-timeout","PT20S"));
  assertEquals(java.time.Duration.ofSeconds(3),settings.connectTimeout());assertEquals(java.time.Duration.ofSeconds(20),settings.readTimeout());
 }
}
