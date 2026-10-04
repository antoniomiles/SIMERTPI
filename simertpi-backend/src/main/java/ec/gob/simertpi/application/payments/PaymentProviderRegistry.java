package ec.gob.simertpi.application.payments;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class PaymentProviderRegistry {
 private final Map<String,PaymentProvider> providers=new HashMap<>();private final Environment env;
 public PaymentProviderRegistry(List<PaymentProvider> adapters,Environment env) {
  this.env=env;
  for(var adapter:adapters)if(providers.putIfAbsent(adapter.providerCode(),adapter)!=null)throw new IllegalStateException("Duplicate payment adapter");
 }
 public PaymentProvider selected() {return resolve(env.getProperty("simertpi.payments.provider","UNCONFIGURED"));}
 public PaymentProvider resolve(String code) {
  var provider=providers.get(code);
  if(provider==null || "UNCONFIGURED".equals(code))throw new PaymentProviderUnavailableException();
  return provider;
 }
}
