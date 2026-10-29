package ec.gob.simertpi.application.identity;

import ec.gob.simertpi.application.audit.Audited;

import ec.gob.simertpi.api.identity.CreateUserRequest;
import ec.gob.simertpi.domain.identity.entity.Role;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.RoleRepository;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public UserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbc) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Transactional
    @Audited(action = "USER_CREATED", resourceType = "USER")
    public User createUser(CreateUserRequest request) {

        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('username:' || lower(?), 0))", rs -> null, username);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(lower(?), 0))", rs -> null, email);

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("El username ya existe");
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("El email ya existe");
        }

        Role citizenRole = roleRepository.findByCode("CITIZEN")
                .orElseThrow(() ->
                        new IllegalStateException(
                                "El rol CITIZEN no existe en la base de datos"));

        OffsetDateTime now = OffsetDateTime.now();

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setPhone(request.phone());
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        user.getRoles().add(citizenRole);

        return userRepository.save(user);
    }
}
