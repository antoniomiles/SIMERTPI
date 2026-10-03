package ec.gob.simertpi.api.notifications;

import ec.gob.simertpi.api.notifications.dto.NotificationResponse;
import ec.gob.simertpi.application.notifications.NotificationInboxService;
import ec.gob.simertpi.domain.notification.entity.Notification;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationInboxService inbox;

    public NotificationController(NotificationInboxService inbox) { this.inbox = inbox; }

    @GetMapping("/mine")
    public List<NotificationResponse> mine(Authentication authentication) {
        return inbox.mine(authentication.getName()).stream().map(this::toResponse).toList();
    }

    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable UUID id, Authentication authentication) {
        return toResponse(inbox.markRead(authentication.getName(), id));
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getNotificationType(),
                notification.getChannel(), notification.getTitle(), notification.getMessage(),
                notification.getStatus(), notification.getReferenceType(), notification.getReferenceId(),
                notification.getCreatedAt(), notification.getSentAt(), notification.getReadAt());
    }
}
