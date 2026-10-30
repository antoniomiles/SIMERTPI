package ec.gob.simertpi.testsupport;

import ec.gob.simertpi.infrastructure.identity.invitation.InMemoryInvitationEmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class InvitationMailTestConfiguration {
    @Bean
    @Primary
    InMemoryInvitationEmailSender invitationEmailSender() {
        return new InMemoryInvitationEmailSender();
    }
}
