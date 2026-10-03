package ec.gob.simertpi.application.vehicles;

import ec.gob.simertpi.api.vehicles.CreateVehicleRequest;
import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import ec.gob.simertpi.domain.vehicles.entity.Vehicle;
import ec.gob.simertpi.domain.vehicles.repository.VehicleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

@Service
public class VehicleService {

    private static final Set<String> STAFF_ROLES = Set.of(
            "INSPECTOR", "SUPERVISOR", "SIMERTPI_ADMIN", "IT_ADMIN", "AUDITOR");

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;

    public VehicleService(VehicleRepository vehicleRepository, UserRepository userRepository) {
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Vehicle createVehicle(CreateVehicleRequest request, String username) {
        User currentUser = userRepository.findByUsernameWithRoles(username)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!currentUser.getId().equals(request.userId())) {
            throw new ForbiddenException();
        }
        if (vehicleRepository.findByPlate(request.plate()).isPresent()) {
            throw new IllegalArgumentException("La placa ya está registrada");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setId(UUID.randomUUID());
        vehicle.setUserId(request.userId());
        vehicle.setPlate(request.plate());
        vehicle.setBrand(request.brand());
        vehicle.setModel(request.model());
        vehicle.setColor(request.color());
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);

        try {
            return vehicleRepository.save(vehicle);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("La placa ya está registrada");
        }
    }

    @Transactional(readOnly = true)
    public List<Vehicle> findByUserId(UUID userId, String username) {
        User currentUser = userRepository.findByUsernameWithRoles(username)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!currentUser.getId().equals(userId) && !hasStaffRole(currentUser)) {
            throw new ForbiddenException();
        }
        return vehicleRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<Vehicle> findByPlate(String plate, String username) {
        User currentUser = userRepository.findByUsernameWithRoles(username)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        if (!hasStaffRole(currentUser)) {
            throw new ForbiddenException();
        }
        return vehicleRepository.findByPlate(plate);
    }

    private boolean hasStaffRole(User user) {
        return user.getRoles().stream().anyMatch(role -> STAFF_ROLES.contains(role.getCode()));
    }
}
