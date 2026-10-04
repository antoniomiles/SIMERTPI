package ec.gob.simertpi.application.payments;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.time.Duration;
/** Future HTTP adapters must consume explicit settings; no hidden production defaults. */
@Component
public class PaymentTransportSettings {
 private final Environment env;
 public PaymentTransportSettings(Environment env) {this.env=env;}
 public Duration connectTimeout() {return timeout("simertpi.payments.connect-timeout");}
 public Duration readTimeout() {return timeout("simertpi.payments.read-timeout");}
 private Duration timeout(String key) {
  String value=env.getProperty(key);
  if(value==null)throw new PaymentProviderUnavailableException();
  Duration duration=Duration.parse(value);
  if(duration.isNegative() || duration.isZero())throw new PaymentProviderUnavailableException();
  return duration;
 }
}
