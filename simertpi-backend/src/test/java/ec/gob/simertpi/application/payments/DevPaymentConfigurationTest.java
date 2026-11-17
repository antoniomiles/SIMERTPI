package ec.gob.simertpi.application.payments;

import ec.gob.simertpi.infrastructure.payments.SandboxPaymentProvider;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class DevPaymentConfigurationTest {
    @Test
    void devDefaultsApproveDeterministicallyInTheBackend() throws Exception {
        try (var context = context("dev")) {
            var provider = context.getBean(PaymentProviderRegistry.class).selected();
            var request = new PaymentProviderRequest(UUID.randomUUID(), UUID.randomUUID(),
                    BigDecimal.ONE, "USD", "dev-checkout", UUID.randomUUID(), null);
            var result = provider.createPayment(request);
            assertEquals("SANDBOX_STUB", provider.providerCode());
            assertEquals(ProviderPaymentStatus.APPROVED, result.status());
            assertEquals(result, provider.createPayment(request));
            assertEquals(result, provider.queryPayment(result.providerPaymentId(), request));
        }
    }

    @Test
    void productionCannotSelectSimulatorEvenWithDevPropertiesPresent() throws Exception {
        try (var context = context("prod")) {
            assertTrue(context.getBeansOfType(SandboxPaymentProvider.class).isEmpty());
            assertThrows(PaymentProviderUnavailableException.class,
                    () -> context.getBean(PaymentProviderRegistry.class).selected());
        }
    }

    private AnnotationConfigApplicationContext context(String profile) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        for (var source : new YamlPropertySourceLoader().load("dev-payments",
                new ClassPathResource("application-dev.yml"))) {
            context.getEnvironment().getPropertySources().addLast(source);
        }
        context.register(SandboxPaymentProvider.class, PaymentProviderRegistry.class);
        context.refresh();
        return context;
    }
}
