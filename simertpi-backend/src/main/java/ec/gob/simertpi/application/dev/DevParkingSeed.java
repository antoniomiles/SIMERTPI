package ec.gob.simertpi.application.dev;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Set;

/** Explicit, transactional DEV fixture; never a Flyway migration or public endpoint. */
@Component
@ConditionalOnProperty(name = "simertpi.dev-seed.enabled", havingValue = "true")
public class DevParkingSeed implements ApplicationRunner {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DevParkingSeed(Environment environment, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.environment = environment;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        requireDev(environment);
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(2152026)");
            var script = new ResourceDatabasePopulator(new ClassPathResource("dev/pinas-parking.sql"));
            script.setSqlScriptEncoding("UTF-8");
            script.execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        });
    }

    static void requireDev(Environment environment) {
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (!profiles.contains("dev") || !Set.of("dev", "test").containsAll(profiles)) {
            throw new IllegalStateException("DEV seed requires an isolated dev profile; never QA/UAT/PROD");
        }
    }
}
