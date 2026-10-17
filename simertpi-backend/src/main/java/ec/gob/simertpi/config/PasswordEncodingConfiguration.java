package ec.gob.simertpi.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
@Configuration(proxyBeanMethods=false)
public class PasswordEncodingConfiguration {
 @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
