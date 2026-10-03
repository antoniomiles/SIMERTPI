package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InspectorAuthorizationService {
    private final UserRepository users;

    public InspectorAuthorizationService(UserRepository users) {
        this.users = users;
    }

    public User requireInspector(String username) {
        User user = users.findByUsernameWithRoles(username)
                .orElseThrow(() -> new ResourceNotFoundException("Inspector not found"));
        if (!user.isEnabled() || user.getRoles().stream()
                .noneMatch(role -> "INSPECTOR".equals(role.getCode()))) {
            throw new ForbiddenException();
        }
        return user;
    }

    public User requireEvidenceAccess(String username, java.util.UUID inspectorId) {
        User user = users.findByUsernameWithRoles(username)
                .orElseThrow(() -> new ResourceNotFoundException("Municipal user not found"));
        if (!user.isEnabled()) throw new ForbiddenException();
        boolean inspector = user.getRoles().stream().anyMatch(role -> "INSPECTOR".equals(role.getCode()));
        boolean supervisorOrAdmin = user.getRoles().stream().anyMatch(role ->
                "SUPERVISOR".equals(role.getCode()) || "SIMERTPI_ADMIN".equals(role.getCode()));
        if (!inspector && !supervisorOrAdmin) throw new ForbiddenException();
        if (inspector && !supervisorOrAdmin && !user.getId().equals(inspectorId)) throw new ForbiddenException();
        return user;
    }
}
