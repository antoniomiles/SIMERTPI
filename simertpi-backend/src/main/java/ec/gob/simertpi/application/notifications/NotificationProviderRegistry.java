package ec.gob.simertpi.application.notifications;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import jakarta.annotation.PostConstruct;
import java.util.*;
@Component
public class NotificationProviderRegistry {
 private final Environment env;
 private final List<NotificationProvider> providers;
 public NotificationProviderRegistry(Environment env,List<NotificationProvider> providers) { this.env=env;this.providers=List.copyOf(providers); }
 public String configuredCode(String channel) {
  if(!Set.of("PUSH","WHATSAPP","EMAIL").contains(channel)) throw new IllegalArgumentException("Invalid notification channel");
  String code=env.getProperty("simertpi.notifications.providers."+channel.toLowerCase(Locale.ROOT),"UNCONFIGURED").toUpperCase(Locale.ROOT);
  return code.matches("[A-Z][A-Z0-9_]{0,39}")?code:"UNKNOWN";
 }
 @PostConstruct public void validate() {
  for(String channel:List.of("PUSH","WHATSAPP","EMAIL")) {
   long count=providers.stream().filter(p->p.channels().contains(channel)&&p.code().equals(configuredCode(channel))).count();
   if(count>1) throw new IllegalStateException("Ambiguous notification provider");
  }
 }
 public Optional<NotificationProvider> resolve(String channel,String code) {
  return providers.stream().filter(p->p.channels().contains(channel)&&p.code().equals(code)).findFirst();
 }
 public NotificationProviderResult send(String code,NotificationProviderRequest request) {
  if("UNCONFIGURED".equals(code)) return NotificationProviderResult.failure(NotificationProviderResult.Status.TEMPORARY_FAILURE,code,true,"PROVIDER_NOT_CONFIGURED");
  return resolve(request.channel(),code).map(p->p.send(request)).orElseGet(()->NotificationProviderResult.failure(
   NotificationProviderResult.Status.PERMANENT_FAILURE,code,false,"PROVIDER_UNKNOWN"));
 }
}
