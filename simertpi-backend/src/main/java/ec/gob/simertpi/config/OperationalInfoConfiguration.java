package ec.gob.simertpi.config;
import org.springframework.context.annotation.*;
import org.springframework.boot.actuate.info.InfoContributor;
@Configuration
public class OperationalInfoConfiguration {
 @Bean InfoContributor safeApplicationInfo(){return builder->builder.withDetail("application",java.util.Map.of("name","simertpi-backend"));}
}
