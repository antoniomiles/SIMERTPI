package ec.gob.simertpi.application.identity;

import ec.gob.simertpi.api.identity.CreateUserRequest;
import ec.gob.simertpi.domain.identity.entity.Role;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.RoleRepository;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class UserServiceTest {

    @Test
    void storesEncodedPasswordForNewCitizen() {
        UserRepository userRepository = mock(UserRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        UserService service = new UserService(userRepository, roleRepository, encoder);
        Role citizen = new Role();
        citizen.setCode("CITIZEN");
        when(roleRepository.findByCode("CITIZEN")).thenReturn(Optional.of(citizen));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        String rawPassword = "citizen-password-123";
        User created = service.createUser(new CreateUserRequest(
                "citizen", "citizen@example.test", rawPassword, "Citizen", "Test", null));

        assertNotEquals(rawPassword, created.getPasswordHash());
        assertTrue(encoder.matches(rawPassword, created.getPasswordHash()));
        verify(userRepository).save(any(User.class));
    }
}
