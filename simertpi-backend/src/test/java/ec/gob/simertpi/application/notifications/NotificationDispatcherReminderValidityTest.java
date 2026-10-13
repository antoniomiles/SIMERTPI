package ec.gob.simertpi.application.notifications;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ec.gob.simertpi.application.audit.AuditService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
class NotificationDispatcherReminderValidityTest {
    @Test void expiryFromOldContractIsSuppressedAfterExtension() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);UUID id=UUID.randomUUID();
        var end=Timestamp.from(Instant.parse("2026-10-13T15:00:00Z"));
        when(jdbc.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("status","EXTENDED","expected_end_at",end)));
        var dispatcher=new NotificationDispatcher(jdbc,mock(PlatformTransactionManager.class),mock(NotificationProviderRegistry.class),mock(NotificationRetryPolicy.class),mock(AuditService.class));
        assertThat(dispatcher.currentReminder(Map.of("notification_type","PARKING_TIME_EXPIRED","reference_id",id,"created_at",end),OffsetDateTime.now())).isFalse();
    }
    @Test void onlyCurrentExpiredContractMayBeDelivered() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);UUID id=UUID.randomUUID();
        var end=Timestamp.from(Instant.parse("2026-10-13T15:00:00Z"));
        var created=Timestamp.from(end.toInstant().plusSeconds(60));
        when(jdbc.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("status","EXPIRED","expected_end_at",end)));
        var dispatcher=new NotificationDispatcher(jdbc,mock(PlatformTransactionManager.class),mock(NotificationProviderRegistry.class),mock(NotificationRetryPolicy.class),mock(AuditService.class));
        assertThat(dispatcher.currentReminder(Map.of("notification_type","PARKING_TIME_EXPIRED","reference_id",id,"created_at",created),OffsetDateTime.now())).isTrue();
        when(jdbc.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("status","EXPIRED","expected_end_at",Timestamp.from(end.toInstant().plusSeconds(120)))));
        assertThat(dispatcher.currentReminder(Map.of("notification_type","PARKING_TIME_EXPIRED","reference_id",id,"created_at",created),OffsetDateTime.now())).isFalse();
    }
}
