package ec.gob.simertpi.api.notifications;
import ec.gob.simertpi.application.notifications.NotificationSettingsService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.*;
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationSettingsController {
 private final NotificationSettingsService settings;
 public NotificationSettingsController(NotificationSettingsService settings){this.settings=settings;}
 public record DeviceRegistration(String platform,String token){@Override public String toString(){return "DeviceRegistration[platform="+platform+"]";}}
 @PostMapping("/devices") public NotificationSettingsService.DeviceView register(Authentication auth,@RequestBody DeviceRegistration request){return settings.register(auth.getName(),request.platform(),request.token());}
 @GetMapping("/devices") public List<NotificationSettingsService.DeviceView> devices(Authentication auth){return settings.devices(auth.getName());}
 @DeleteMapping("/devices/{id}") public void disable(Authentication auth,@PathVariable UUID id){settings.disable(auth.getName(),id);}
 @GetMapping("/preferences") public List<NotificationSettingsService.PreferenceView> preferences(Authentication auth){return settings.preferences(auth.getName());}
 @PutMapping("/preferences") public List<NotificationSettingsService.PreferenceView> preferences(Authentication auth,@RequestBody Map<String,Boolean> request){return settings.preferences(auth.getName(),request);}
}
