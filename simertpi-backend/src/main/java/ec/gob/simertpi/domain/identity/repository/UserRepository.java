package ec.gob.simertpi.domain.identity.repository;

import ec.gob.simertpi.domain.identity.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    @EntityGraph(attributePaths = "roles")
    @Query("SELECT u FROM User u WHERE u.username = :username")
    Optional<User> findByUsernameWithRoles(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    @Query(value = """
            SELECT u.id AS id, u.username AS username, u.email AS email,
                   u.firstName AS firstName, u.lastName AS lastName, u.phone AS phone,
                   u.enabled AS enabled,
                   (u.enabled = FALSE AND u.passwordHash IS NULL) AS activationPending,
                   u.createdAt AS createdAt
            FROM User u JOIN u.roles r
            WHERE r.code = 'INSPECTOR'
              AND (:enabled IS NULL OR u.enabled = :enabled)
              AND (:query = '' OR
                   LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :query, '%')))
            """,
            countQuery = """
            SELECT COUNT(u.id) FROM User u JOIN u.roles r
            WHERE r.code = 'INSPECTOR'
              AND (:enabled IS NULL OR u.enabled = :enabled)
              AND (:query = '' OR
                   LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :query, '%')))
            """)
    Page<InspectorDirectoryProjection> searchInspectors(@Param("query") String query,
                                                        @Param("enabled") Boolean enabled,
                                                        Pageable pageable);
}
