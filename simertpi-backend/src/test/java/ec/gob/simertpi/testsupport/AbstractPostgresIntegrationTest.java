package ec.gob.simertpi.testsupport;

import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/** One lazy container per Surefire JVM; unit tests never initialize this holder. */
@Tag("integration")
@Import(IntegrationTestConfiguration.class)
public abstract class AbstractPostgresIntegrationTest {

    private static class Infrastructure {
        private static final PostgreSQLContainer<?> POSTGRES = startPostgres();
        private static final Path STORAGE = temporaryStorage();

        private static PostgreSQLContainer<?> startPostgres() {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16.15")
                    .withDatabaseName("simertpi_test")
                    .withUsername("qa")
                    .withPassword(UUID.randomUUID().toString());
            container.start();
            return container; // Ryuk owns cleanup, including an interrupted Maven JVM.
        }

        private static Path temporaryStorage() {
            try {
                Path directory = Files.createTempDirectory("simertpi-qa-storage-");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try (var paths = Files.walk(directory)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    } catch (IOException ignored) {
                        // Never log object names/paths; all storage is outside the repository.
                    }
                }, "qa-storage-cleanup"));
                return directory;
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot initialize temporary QA storage");
            }
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", Infrastructure.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", Infrastructure.POSTGRES::getUsername);
        registry.add("spring.datasource.password", Infrastructure.POSTGRES::getPassword);
        // A separate placeholder preserves class-specific storage overrides regardless of
        // Spring's order of inherited DynamicPropertySource methods.
        registry.add("simertpi.qa.storage-path", Infrastructure.STORAGE::toString);
    }

    protected static PostgreSQLContainer<?> postgresContainer() {
        return Infrastructure.POSTGRES;
    }
}
