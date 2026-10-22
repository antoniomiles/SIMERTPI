package ec.gob.simertpi.application.parking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** Coordinates effective tariff changes with the tariff selection used to start a session. */
@Component
public class TariffScopeLockService {
    private final JdbcTemplate jdbc;

    public TariffScopeLockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockScopes(UUID... zoneIds) {
        lockScopes(Arrays.asList(zoneIds));
    }

    public void lockScopes(Collection<UUID> zoneIds) {
        zoneIds.stream().distinct()
                .map(zoneId -> "tariff-scope:" + (zoneId == null ? "GENERAL" : zoneId))
                .sorted()
                .forEach(this::lock);
    }

    public void lockVersionCodes(String... codes) {
        Arrays.stream(codes).filter(Objects::nonNull).map(String::trim).filter(code -> !code.isEmpty())
                .map(code -> "tariff-version-code:" + code).distinct().sorted().forEach(this::lock);
    }

    public OffsetDateTime databaseNow() {
        return jdbc.queryForObject("SELECT date_trunc('microseconds', clock_timestamp())", OffsetDateTime.class);
    }

    private void lock(String key) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                statement.setString(1, key);
                statement.execute();
            }
            return null;
        });
    }
}
