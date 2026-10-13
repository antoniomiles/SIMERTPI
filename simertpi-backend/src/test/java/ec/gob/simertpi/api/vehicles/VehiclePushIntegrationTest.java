package ec.gob.simertpi.api.vehicles;
import ec.gob.simertpi.application.vehicles.VehicleService;
import ec.gob.simertpi.application.notifications.*;
import ec.gob.simertpi.infrastructure.notifications.*;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.util.*;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real local PostgreSQL transactions and dispatcher, mocked FCM boundary. No remote delivery. */
@SpringBootTest(properties={"simertpi.notifications.outbox.enabled=false","simertpi.notifications.dispatcher.enabled=false"})
class VehiclePushIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {
    @Autowired VehicleService vehicles;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationOutboxProcessor outbox;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean NotificationProviderRegistry registry;
    @MockitoBean FirebaseCloudMessagingClient firebase;
    User owner,other;
    @BeforeEach void setup() throws Exception {
        owner=user();other=user();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.getUsername(),null,List.of()));
        when(registry.configuredCode("PUSH")).thenReturn("FCM");
        when(firebase.send(any())).thenReturn("projects/simertpi-dev/messages/local-test");
        when(registry.send(eq("FCM"),any())).thenAnswer(i->new FcmPushNotificationProvider(firebase).send(i.getArgument(1)));
        for(var u:List.of(owner,other)) {
            jdbc.update("INSERT INTO notification.preferences(user_id,channel,enabled) VALUES(?,'PUSH',true)",u.getId());
            String token="local-test-device-"+u.getId();
            jdbc.update("INSERT INTO notification.devices(id,user_id,platform,token,token_hash) VALUES(?,?,'ANDROID',?,?)",
                UUID.randomUUID(),u.getId(),token,NotificationSettingsService.hash(token));
        }
    }
    @AfterEach void clearAuthentication() {
        // Own synthetic fixture only; keep unrelated events from leaking between tests.
        jdbc.update("DELETE FROM notification.notifications WHERE user_id IN (?,?)",owner.getId(),other.getId());
        jdbc.update("DELETE FROM audit.outbox_events WHERE aggregate_type='VEHICLE' AND payload::jsonb->>'ownerId' IN (?,?)",owner.getId().toString(),other.getId().toString());
        SecurityContextHolder.clearContext();
    }
    User user() {
        var u=new User();u.setId(UUID.randomUUID());u.setUsername("vehicle-push-"+u.getId());
        u.setEmail(u.getUsername()+"@example.test");u.setPasswordHash("local-test-only");
        u.setFirstName("QA");u.setLastName("Citizen");u.setEnabled(true);
        u.setCreatedAt(OffsetDateTime.now());u.setUpdatedAt(OffsetDateTime.now());return users.save(u);
    }
    CreateVehicleRequest request(String plate) { return new CreateVehicleRequest(owner.getId(),plate,"QA","QA","QA"); }
    long events() { return jdbc.queryForObject("SELECT count(*) FROM audit.outbox_events WHERE aggregate_type='VEHICLE' AND payload::jsonb->>'ownerId'=?",Long.class,owner.getId().toString()); }
    void deliver() { outbox.processPending();dispatcher.processDue(OffsetDateTime.now()); }
    @Test void registrationPushUsesPlateAndOnlyOwnerDeviceWithoutAddingInboxItems() throws Exception {
        var v=vehicles.createVehicle(request("ABC-1234"),owner.getUsername());deliver();
        var captured=org.mockito.ArgumentCaptor.forClass(NotificationProviderRequest.class);verify(firebase).send(captured.capture());
        assertThat(captured.getValue().destination()).isEqualTo("local-test-device-"+owner.getId());
        assertThat(captured.getValue().data()).containsEntry("eventType","VEHICLE_REGISTERED")
            .containsEntry("recipientOwnerId",owner.getId().toString()).containsEntry("plate","ABC-1234");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.inbox_items WHERE user_id=?",Long.class,owner.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE user_id=?",Long.class,other.getId())).isZero();
        assertThat(events()).isEqualTo(1);
    }
    @Test void repeatedDeletionAndOutboxReplayDoNotDuplicateDelivery() throws Exception {
        var v=vehicles.createVehicle(request("ABC-1234"),owner.getUsername());deliver();clearInvocations(firebase);
        vehicles.deactivate(v.getId(),owner.getUsername());vehicles.deactivate(v.getId(),owner.getUsername());
        deliver();deliver();
        assertThat(events()).isEqualTo(2);
        var captured=org.mockito.ArgumentCaptor.forClass(NotificationProviderRequest.class);verify(firebase,times(1)).send(captured.capture());
        assertThat(captured.getValue().data()).containsEntry("eventType","VEHICLE_DELETED").containsEntry("plate","ABC-1234");
    }
    @Test void rejectedOperationsNeverEnqueueAnotherEvent() {
        var v=vehicles.createVehicle(request("ABC-1234"),owner.getUsername());
        assertThatThrownBy(()->vehicles.createVehicle(request("ABC-1234"),owner.getUsername())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->vehicles.deactivate(v.getId(),other.getUsername())).isInstanceOf(ec.gob.simertpi.api.ForbiddenException.class);
        assertThatThrownBy(()->vehicles.createVehicle(request("DEF-4321"),other.getUsername())).isInstanceOf(ec.gob.simertpi.api.ForbiddenException.class);
        assertThat(events()).isEqualTo(1);verifyNoInteractions(firebase);
    }
    @Test void rolledBackVehicleOperationRollsBackOutboxToo() {
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(t->{
            vehicles.createVehicle(request("ABC-1234"),owner.getUsername());throw new IllegalStateException("local rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(events()).isZero();assertThat(vehicles.findByUserId(owner.getId(),owner.getUsername())).isEmpty();
        deliver();verifyNoInteractions(firebase);
    }
    @Test void disabledPushPreferencePreventsGeneration() {
        jdbc.update("UPDATE notification.preferences SET enabled=false WHERE user_id=? AND channel='PUSH'",owner.getId());
        vehicles.createVehicle(request("ABC-1234"),owner.getUsername());deliver();
        verifyNoInteractions(firebase);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE user_id=?",Long.class,owner.getId())).isZero();
    }
    @Test void preferenceRevokedAfterQueuingSuppressesDelivery() {
        vehicles.createVehicle(request("ABC-1234"),owner.getUsername());outbox.processPending();
        jdbc.update("UPDATE notification.preferences SET enabled=false WHERE user_id=? AND channel='PUSH'",owner.getId());
        dispatcher.processDue(OffsetDateTime.now());verifyNoInteractions(firebase);
        assertThat(jdbc.queryForObject("SELECT d.status FROM notification.deliveries d JOIN notification.notifications n ON n.id=d.notification_id WHERE n.user_id=?",String.class,owner.getId())).isEqualTo("SUPPRESSED");
    }
    @Test void acceptedOpaqueFcmIdSurvivesDispatcherWithoutClaimingPhoneReceipt() throws Exception {
        when(firebase.send(any())).thenReturn("projects/simertpi-dev/messages/0:123%opaque+");
        vehicles.createVehicle(request("ABC-1234"),owner.getUsername());deliver();
        var row=jdbc.queryForMap("SELECT d.status,d.external_message_id,d.error_code FROM notification.deliveries d JOIN notification.notifications n ON n.id=d.notification_id WHERE n.user_id=?",owner.getId());
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(row.get("external_message_id")).isEqualTo("projects/simertpi-dev/messages/0:123%opaque+");
        assertThat(row.get("error_code")).isNull();
    }
    @Test void dispatcherRetainsSafeFcmErrorInsteadOfGenericUnknown() throws Exception {
        when(firebase.send(any())).thenReturn(null);
        vehicles.createVehicle(request("ABC-1234"),owner.getUsername());deliver();
        var row=jdbc.queryForMap("SELECT d.status,d.error_code FROM notification.deliveries d JOIN notification.notifications n ON n.id=d.notification_id WHERE n.user_id=?",owner.getId());
        assertThat(row.get("status")).isEqualTo("UNKNOWN");
        assertThat(row.get("error_code")).isEqualTo("FCM_RESULT_INVALID");
    }
}
