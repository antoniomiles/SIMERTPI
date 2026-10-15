package ec.gob.simertpi.application.identity.auth;

import ec.gob.simertpi.domain.identity.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Municipal-facing facade over the shared, audience-scoped mobile session store. */
@Service
public class MunicipalAuthService {
    private final MobileAuthService sessions;
    private final UserRepository users;

    public MunicipalAuthService(MobileAuthService sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    public record IdentityResponse(UUID userId, String username, List<String> roles, boolean enabled) {}

    public MobileAuthService.SessionResponse login(String username, String password) {
        return sessions.municipalLogin(username, password);
    }

    public MobileAuthService.SessionResponse refresh(String refreshToken) {
        return sessions.municipalRefresh(refreshToken);
    }

    public void logout(String refreshToken) {
        sessions.municipalLogout(refreshToken);
    }

    public UserDetails authenticate(String accessToken) {
        return sessions.authenticateMunicipal(accessToken);
    }

    public boolean isMunicipalToken(String accessToken) {
        return sessions.isMunicipalToken(accessToken);
    }

    @Transactional(readOnly = true)
    public IdentityResponse me(String accessToken) {
        UserDetails authenticated = authenticate(accessToken);
        var user = users.findByUsernameWithRoles(authenticated.getUsername())
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Authentication required"));
        var roles = user.getRoles().stream().map(role -> role.getCode()).sorted().toList();
        return new IdentityResponse(user.getId(), user.getUsername(), roles, user.isEnabled());
    }
}
