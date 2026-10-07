package ec.gob.simertpi.api.citizen;

import ec.gob.simertpi.application.citizen.CitizenActivityService;
import ec.gob.simertpi.application.citizen.CitizenActivityService.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CitizenActivityController {
    private final CitizenActivityService service;
    public CitizenActivityController(CitizenActivityService service) { this.service = service; }
    @GetMapping("/citizen/profile")
    public Profile profile(Authentication auth) { return service.profile(auth.getName()); }
    @GetMapping("/citizen/history")
    public Page<History> history(Authentication auth, @RequestParam(defaultValue="20") int limit,
                                 @RequestParam(defaultValue="0") int offset) {
        return service.history(auth.getName(), limit, offset);
    }
    @GetMapping("/citizen/history/{id}")
    public HistoryDetail detail(Authentication auth, @PathVariable UUID id) {
        return service.historyDetail(auth.getName(), id);
    }
    @GetMapping("/notifications/inbox")
    public Page<InboxItem> inbox(Authentication auth, @RequestParam(defaultValue="20") int limit,
                                  @RequestParam(defaultValue="0") int offset) {
        return service.inbox(auth.getName(), limit, offset);
    }
    @GetMapping("/notifications/inbox/{id}")
    public InboxItem item(Authentication auth, @PathVariable UUID id) { return service.inboxDetail(auth.getName(), id); }
    @PatchMapping("/notifications/inbox/{id}/read")
    public InboxItem read(Authentication auth, @PathVariable UUID id) { return service.read(auth.getName(), id); }
    @GetMapping("/notifications/unread-count")
    public UnreadCount unread(Authentication auth) { return service.unread(auth.getName()); }
}
