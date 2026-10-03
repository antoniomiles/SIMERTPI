package ec.gob.simertpi.domain.configuration.repository;

import ec.gob.simertpi.domain.configuration.entity.NotificationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

public interface NotificationRuleRepository
        extends JpaRepository<NotificationRule, UUID> {

    List<NotificationRule> findByEventTypeAndEnabledTrue(String eventType);

    @Query("select r from NotificationRule r where r.eventType = :eventType and r.enabled = true "
            + "and r.validFrom <= :at and (r.validTo is null or r.validTo >= :at)")
    List<NotificationRule> findActiveForEventAt(@Param("eventType") String eventType,
                                                @Param("at") OffsetDateTime at);
}
