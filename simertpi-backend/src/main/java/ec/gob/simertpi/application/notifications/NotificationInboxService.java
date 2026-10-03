package ec.gob.simertpi.application.notifications;

import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.notification.entity.Notification;
import ec.gob.simertpi.domain.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationInboxService {
    private final UserRepository users;
    private final NotificationRepository notifications;

    public NotificationInboxService(UserRepository users, NotificationRepository notifications) {
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<Notification> mine(String username) {
        User user = authenticated(username);
        return notifications.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public Notification markRead(String username, UUID id) {
        User user = authenticated(username);
        Notification notification = notifications.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notificación no encontrada"));
        if (!"READ".equals(notification.getStatus())) {
            notification.setStatus("READ");
            notification.setReadAt(OffsetDateTime.now());
            notification.setUpdatedAt(notification.getReadAt());
            notifications.save(notification);
        }
        return notification;
    }

    private User authenticated(String username) {
        return users.findByUsername(username).filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario autenticado no encontrado"));
    }
}
