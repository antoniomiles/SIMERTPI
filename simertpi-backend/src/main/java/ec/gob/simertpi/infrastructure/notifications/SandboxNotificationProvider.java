package ec.gob.simertpi.infrastructure.notifications;
import ec.gob.simertpi.application.notifications.*;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import jakarta.annotation.PostConstruct;
import java.util.*;
@Component
public class SandboxNotificationProvider implements NotificationProvider {
 private final Environment env;
 public SandboxNotificationProvider(Environment env) { this.env=env; }
 private boolean permitted() {
  Set<String> profiles=new HashSet<>(Arrays.asList(env.getActiveProfiles()));
  return env.getProperty("simertpi.notifications.sandbox.enabled",Boolean.class,false)
   && !profiles.isEmpty() && profiles.stream().allMatch(p->Set.of("dev","test").contains(p));
 }
 @PostConstruct public void validate() {
  if(env.getProperty("simertpi.notifications.sandbox.enabled",Boolean.class,false)&&!permitted())
   throw new IllegalStateException("Notification sandbox requires exclusively dev/test profiles");
 }
 @Override public String code(){return "SANDBOX";}
 @Override public Set<String> channels(){return Set.of("PUSH","WHATSAPP","EMAIL");}
 @Override public boolean supportsIdempotency(){return true;}
 @Override public NotificationProviderResult send(NotificationProviderRequest request) {
  if(!permitted()) return NotificationProviderResult.failure(NotificationProviderResult.Status.PERMANENT_FAILURE,code(),false,"SANDBOX_DISABLED");
  if(!channels().contains(request.channel())) throw new IllegalArgumentException("Unsupported channel");
  NotificationProviderResult.Status status;
  try { status=NotificationProviderResult.Status.valueOf(env.getProperty("simertpi.notifications.sandbox.outcome","PENDING")); }
  catch(IllegalArgumentException invalid){status=NotificationProviderResult.Status.UNKNOWN;}
  return new NotificationProviderResult(status,code(),status==NotificationProviderResult.Status.DELIVERED?"sandbox-"+request.deliveryId():null,
   status==NotificationProviderResult.Status.TEMPORARY_FAILURE,status==NotificationProviderResult.Status.DELIVERED?null:status.name());
 }
}
