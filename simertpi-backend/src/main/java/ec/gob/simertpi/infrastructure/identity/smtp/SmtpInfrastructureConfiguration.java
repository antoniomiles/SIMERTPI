package ec.gob.simertpi.infrastructure.identity.smtp;

import ec.gob.simertpi.application.identity.smtp.SmtpTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SmtpInfrastructureConfiguration {
    @Bean
    public SmtpTransport smtpTransport() { return new JavaMailSmtpTransport(); }
}
