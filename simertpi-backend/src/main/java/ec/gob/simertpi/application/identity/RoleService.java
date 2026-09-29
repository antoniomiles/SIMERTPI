package ec.gob.simertpi.application.identity;

import ec.gob.simertpi.domain.identity.entity.Role;
import ec.gob.simertpi.domain.identity.repository.RoleRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RoleService {

    private final RoleRepository roleRepository;

    public RoleService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    public Optional<Role> findByCode(String code) {
        return roleRepository.findByCode(code);
    }

    public boolean existsByCode(String code) {
        return roleRepository.existsByCode(code);
    }
}