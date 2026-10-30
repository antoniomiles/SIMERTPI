package ec.gob.simertpiadmin.bootstrap;
import ec.gob.simertpi.application.identity.admin.AdminBootstrapService;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.application.identity.InspectorAccountService;
import ec.gob.simertpi.config.PasswordEncodingConfiguration;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
/** Outside ec.gob.simertpi component scan: normal server startup never loads this context. */
@Configuration(proxyBeanMethods=false)
@EnableAutoConfiguration(exclude={FlywayAutoConfiguration.class,SecurityAutoConfiguration.class})
@EntityScan(basePackageClasses=User.class)
@EnableJpaRepositories(basePackageClasses=UserRepository.class)
@ComponentScan(basePackages={"ec.gob.simertpi.application.identity.invitation","ec.gob.simertpi.application.identity.smtp"})
@Import({AdminBootstrapService.class,AuditService.class,PasswordEncodingConfiguration.class,InspectorAccountService.class,
        ec.gob.simertpi.infrastructure.identity.smtp.SmtpInfrastructureConfiguration.class,
        ec.gob.simertpi.infrastructure.identity.invitation.InvitationEmailConfiguration.class})
public class AdminBootstrapConfiguration {}
