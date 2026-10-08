package ec.gob.simertpi.api.notifications;
import ec.gob.simertpi.application.notifications.*;
import ec.gob.simertpi.infrastructure.notifications.SandboxNotificationProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.flywaydb.core.Flyway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "simertpi.notifications.providers.push=SANDBOX","simertpi.notifications.providers.whatsapp=SANDBOX","simertpi.notifications.providers.email=SANDBOX",
 "simertpi.notifications.sandbox.enabled=true","simertpi.notifications.sandbox.outcome=DELIVERED"})
class NotificationProviderHttpIntegrationTest extends ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest {
 @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
 private double metric(String name){var meter=metrics.find(name).counter();return meter==null?0:meter.count();}

 @Autowired ec.gob.simertpi.domain.configuration.repository.NotificationRuleRepository ruleRepository;
 @Autowired JdbcTemplate jdbc;@Autowired NotificationSettingsService settings;@Autowired NotificationGenerationService generation;
 @Autowired NotificationDispatcher dispatcher;@Autowired TestRestTemplate http;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;@Autowired Flyway flyway;
 @MockitoSpyBean NotificationProviderRegistry registry;
 @MockitoSpyBean SandboxNotificationProvider provider;@LocalServerPort int port;
 UUID owner,other;String username,othername;List<UUID> rules;List<UUID> outboxes;
 static final String PASSWORD="notification-test-only";
 @BeforeEach void setup(){
  owner=UUID.randomUUID();other=UUID.randomUUID();username="cp13-"+owner;othername="cp13-"+other;rules=new ArrayList<>();outboxes=new ArrayList<>();
  insertUser(owner,username);insertUser(other,othername);
  settings.preferences(username,Map.of("PUSH",true,"EMAIL",true,"WHATSAPP",true));
 }
 private void insertUser(UUID id,String name){
  jdbc.update("INSERT INTO identity.users(id,username,email,phone,password_hash,first_name,last_name,enabled) VALUES (?,?,?,?,?,'Notify','Fixture',true)",id,name,name+"@example.test","+593999000123",encoder.encode(PASSWORD));
  jdbc.update("INSERT INTO identity.user_roles(user_id,role_id) SELECT ?,id FROM identity.roles WHERE code='CITIZEN'",id);
 }
 @AfterEach void cleanup(){
  jdbc.update("DELETE FROM notification.notifications WHERE user_id IN (?,?)",owner,other);
  jdbc.update("DELETE FROM notification.devices WHERE user_id IN (?,?)",owner,other);
  jdbc.update("DELETE FROM notification.preferences WHERE user_id IN (?,?)",owner,other);
  for(UUID id:outboxes)jdbc.update("DELETE FROM audit.outbox_events WHERE id=?",id);
  for(UUID id:rules)jdbc.update("DELETE FROM configuration.notification_rules WHERE id=?",id);
  jdbc.update("DELETE FROM identity.user_roles WHERE user_id IN (?,?)",owner,other);
  jdbc.update("DELETE FROM identity.users WHERE id IN (?,?)",owner,other);
 }
 private UUID rule(String channel,boolean mandatory){
  UUID id=UUID.randomUUID();rules.add(id);
  jdbc.update("INSERT INTO configuration.notification_rules(id,code,event_type,channel,title_template,message_template,valid_from,mandatory) VALUES (?,?,'PAYMENT_APPROVED',?,'Title','Body',?,?)",id,"CP13-"+id,channel,OffsetDateTime.now().minusDays(1),mandatory);
  return id;
 }
 private UUID notification(String channel,boolean mandatory){
  UUID rule=rule(channel,mandatory),id=UUID.randomUUID();
  jdbc.update("INSERT INTO notification.notifications(id,user_id,notification_type,channel,title,message,status,rule_id) VALUES (?,?,'PAYMENT_APPROVED',?,'Title','Body','PENDING',?)",id,owner,channel,rule);
  dispatcher.prepare(id);return id;
 }
 private NotificationSettingsService.DeviceView device(String platform){return settings.register(username,platform,"sensitive-fixture-"+UUID.randomUUID());}
 private void process(){dispatcher.processDue(OffsetDateTime.now());}
 private String status(UUID id){return jdbc.queryForObject("SELECT status FROM notification.deliveries WHERE notification_id=?",String.class,id);}
 private int deliveries(UUID id){return jdbc.queryForObject("SELECT count(*) FROM notification.deliveries WHERE notification_id=?",Integer.class,id);}
 private int audit(String action,UUID id){return jdbc.queryForObject("SELECT count(*) FROM audit.functional_audit_log WHERE action=? AND resource_id=?",Integer.class,action,id);}
 private UUID deliveryId(UUID id){return jdbc.queryForObject("SELECT id FROM notification.deliveries WHERE notification_id=?",UUID.class,id);}
 private ResponseEntity<String> call(String path,HttpMethod method,String name,Object body){
  HttpHeaders headers=new HttpHeaders();if(name!=null)headers.setBasicAuth(name,PASSWORD);headers.set("X-Correlation-ID","cp13-correlation");
  return http.exchange("http://localhost:"+port+"/api/v1/notifications"+path,method,new HttpEntity<>(body,headers),String.class);
 }
 private void outcome(NotificationProviderResult.Status status){doAnswer(inv->new NotificationProviderResult(status,"SANDBOX",status==NotificationProviderResult.Status.DELIVERED?"safe-fixture-id":null,status==NotificationProviderResult.Status.TEMPORARY_FAILURE,"SENSITIVE_ERROR_MUST_NOT_BE_PERSISTED")).when(provider).send(any());}
 @ParameterizedTest @ValueSource(strings={"ANDROID","IOS","WEB"}) void registersPlatformsThroughHttp(String platform)throws Exception{
  String token="sensitive-fixture-"+UUID.randomUUID();var response=call("/devices",HttpMethod.POST,username,Map.of("platform",platform,"token",token,"userId",other));
  assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(response.getBody()).contains("***",platform).doesNotContain(token);
  UUID id=UUID.fromString(json.readTree(response.getBody()).get("id").asText());assertThat(jdbc.queryForObject("SELECT user_id FROM notification.devices WHERE id=?",UUID.class,id)).isEqualTo(owner);
  assertThat(audit("NOTIFICATION_DEVICE_REGISTERED",id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT correlation_id FROM audit.functional_audit_log WHERE action='NOTIFICATION_DEVICE_REGISTERED' AND resource_id=?",String.class,id)).isEqualTo("cp13-correlation");
 }
 @Test void tokenRegistrationAndDisableAreIdempotent(){String token="private-"+UUID.randomUUID();var first=settings.register(username,"WEB",token);assertThat(settings.register(username,"WEB",token).id()).isEqualTo(first.id());assertThat(settings.devices(username)).hasSize(1);assertThat(audit("NOTIFICATION_DEVICE_REGISTERED",first.id())).isEqualTo(1);settings.disable(username,first.id());settings.disable(username,first.id());assertThat(audit("NOTIFICATION_DEVICE_DISABLED",first.id())).isEqualTo(1);assertThat(settings.devices(username).getFirst().active()).isFalse();}
 @Test void ownDeviceCanBeReactivated(){String token="private-"+UUID.randomUUID();var first=settings.register(username,"WEB",token);settings.disable(username,first.id());assertThat(settings.register(username,"WEB",token).id()).isEqualTo(first.id());assertThat(settings.devices(username).getFirst().active()).isTrue();}
 @Test void deviceTokenCanBeReassociatedAfterAccountSwitchButIdsRemainOwnerScoped(){String token="private-"+UUID.randomUUID();var foreign=settings.register(othername,"ANDROID",token);assertThat(call("/devices",HttpMethod.GET,username,null).getBody()).doesNotContain(foreign.id().toString());assertThat(call("/devices/"+foreign.id(),HttpMethod.DELETE,username,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);var response=call("/devices",HttpMethod.POST,username,Map.of("platform","ANDROID","token",token));assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(settings.devices(othername)).isEmpty();assertThat(settings.devices(username)).singleElement().satisfies(device->assertThat(device.id()).isEqualTo(foreign.id()));assertThat(jdbc.queryForObject("SELECT user_id FROM notification.devices WHERE id=?",UUID.class,foreign.id())).isEqualTo(owner);assertThat(audit("NOTIFICATION_DEVICE_REASSIGNED",foreign.id())).isEqualTo(1);}
 @Test void anonymousCannotManageDevicesOrPreferences(){for(String path:List.of("/devices","/preferences"))assertThat(call(path,HttpMethod.GET,null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);}
 @Test void preferencesAreDisabledByDefault(){assertThat(settings.preferences(othername)).allMatch(p->!p.enabled());}
 @ParameterizedTest @ValueSource(strings={"PUSH","WHATSAPP","EMAIL"}) void preferencesAreOwnedAndIdempotent(String channel){
  assertThat(call("/preferences",HttpMethod.PUT,username,Map.of(channel,false)).getStatusCode()).isEqualTo(HttpStatus.OK);
  int count=audit("NOTIFICATION_PREFERENCE_CHANGED",owner);settings.preferences(username,Map.of(channel,false));assertThat(audit("NOTIFICATION_PREFERENCE_CHANGED",owner)).isEqualTo(count);
  assertThat(settings.preferences(othername)).allMatch(p->!p.enabled());assertThat(settings.preferences(username)).anyMatch(p->channel.equals(p.channel())&&!p.enabled());
 }
 @Test void oneDeviceProducesOneDelivery(){double count=metric("simertpi.notifications.delivered");device("ANDROID");UUID n=notification("PUSH",false);process();assertThat(deliveries(n)).isEqualTo(1);assertThat(status(n)).isEqualTo("DELIVERED");assertThat(metric("simertpi.notifications.delivered")).isEqualTo(count+1);}
 @Test void multiDeviceDoesNotDuplicateInbox(){device("ANDROID");device("IOS");UUID n=notification("PUSH",false);process();dispatcher.prepare(n);process();assertThat(deliveries(n)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE id=?",Integer.class,n)).isEqualTo(1);verify(provider,times(2)).send(any());}
 @Test void pushWithoutDeviceIsNotDelivered(){double count=metric("simertpi.notifications.no_destination");UUID n=notification("PUSH",false);process();assertThat(status(n)).isEqualTo("NO_DESTINATION");assertThat(metric("simertpi.notifications.no_destination")).isEqualTo(count+1);verify(provider,never()).send(any());}
 @Test void invalidDestinationDisablesOnlyThatDevice(){var first=device("ANDROID");var second=device("IOS");doAnswer(inv->{NotificationProviderRequest req=inv.getArgument(0);return req.destination().equals(jdbc.queryForObject("SELECT token FROM notification.devices WHERE id=?",String.class,first.id()))?NotificationProviderResult.failure(NotificationProviderResult.Status.INVALID_DESTINATION,"SANDBOX",false,"INVALID_DESTINATION"):new NotificationProviderResult(NotificationProviderResult.Status.DELIVERED,"SANDBOX","safe-id",false,null);}).when(provider).send(any());UUID n=notification("PUSH",false);process();assertThat(jdbc.queryForObject("SELECT active FROM notification.devices WHERE id=?",Boolean.class,first.id())).isFalse();assertThat(jdbc.queryForObject("SELECT active FROM notification.devices WHERE id=?",Boolean.class,second.id())).isTrue();assertThat(audit("NOTIFICATION_DESTINATION_DISABLED",first.id())).isEqualTo(1);process();assertThat(deliveries(n)).isEqualTo(2);}
 @ParameterizedTest @ValueSource(strings={"WHATSAPP","EMAIL"}) void validContactIsDelivered(String channel){UUID n=notification(channel,false);process();assertThat(status(n)).isEqualTo("DELIVERED");}
 @ParameterizedTest @ValueSource(strings={"WHATSAPP","EMAIL"}) void missingContactIsNoDestination(String channel){jdbc.update("UPDATE identity.users SET "+("EMAIL".equals(channel)?"email":"phone")+("EMAIL".equals(channel)?"=''":"=null")+" WHERE id=?",owner);UUID n=notification(channel,false);process();assertThat(status(n)).isEqualTo("NO_DESTINATION");}
 @ParameterizedTest @ValueSource(strings={"WHATSAPP","EMAIL"}) void malformedContactIsNoDestination(String channel){jdbc.update("UPDATE identity.users SET "+("EMAIL".equals(channel)?"email":"phone")+"='malformed' WHERE id=?",owner);UUID n=notification(channel,false);process();assertThat(status(n)).isEqualTo("NO_DESTINATION");}
 @ParameterizedTest @ValueSource(strings={"PUSH","WHATSAPP","EMAIL"}) void preferenceChangeBeforeDispatchSuppresses(String channel){if("PUSH".equals(channel))device("WEB");UUID n=notification(channel,false);settings.preferences(username,Map.of(channel,false));process();assertThat(status(n)).isEqualTo("SUPPRESSED");verify(provider,never()).send(any());assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE id=?",Integer.class,n)).isEqualTo(1);}
 @Test void mandatoryConfiguredRuleOverridesPreference(){settings.preferences(username,Map.of("EMAIL",false));UUID n=notification("EMAIL",true);process();assertThat(status(n)).isEqualTo("DELIVERED");}
 @Test void temporaryFailureBackoffReusesDelivery(){outcome(NotificationProviderResult.Status.TEMPORARY_FAILURE);UUID n=notification("EMAIL",false);process();UUID delivery=deliveryId(n);assertThat(status(n)).isEqualTo("TEMPORARY_FAILURE");assertThat(jdbc.queryForObject("SELECT next_attempt_at>last_attempt_at FROM notification.deliveries WHERE id=?",Boolean.class,delivery)).isTrue();process();verify(provider,times(1)).send(any());outcome(NotificationProviderResult.Status.DELIVERED);dispatcher.processDue(OffsetDateTime.now().plusSeconds(31));assertThat(status(n)).isEqualTo("DELIVERED");assertThat(deliveryId(n)).isEqualTo(delivery);assertThat(jdbc.queryForObject("SELECT attempts FROM notification.deliveries WHERE id=?",Integer.class,delivery)).isEqualTo(2);}
 @Test void permanentFailureDoesNotRetry(){outcome(NotificationProviderResult.Status.PERMANENT_FAILURE);UUID n=notification("EMAIL",false);process();dispatcher.processDue(OffsetDateTime.now().plusDays(1));assertThat(status(n)).isEqualTo("PERMANENT_FAILURE");verify(provider,times(1)).send(any());assertThat(audit("NOTIFICATION_PERMANENT_FAILURE",deliveryId(n))).isEqualTo(1);}
 @Test void unknownResultNeverDeliveredOrBlindlyRetried(){outcome(NotificationProviderResult.Status.UNKNOWN);UUID n=notification("EMAIL",false);process();dispatcher.processDue(OffsetDateTime.now().plusDays(1));assertThat(status(n)).isEqualTo("UNKNOWN");verify(provider,times(1)).send(any());}
 @Test void providerExceptionSanitizedAndUnknown(){doThrow(new IllegalStateException("SENSITIVE_PROVIDER_SECRET")).when(provider).send(any());UUID n=notification("EMAIL",false);process();assertThat(status(n)).isEqualTo("UNKNOWN");assertThat(jdbc.queryForObject("SELECT error_code FROM notification.deliveries WHERE notification_id=?",String.class,n)).isEqualTo("UNKNOWN");}
 @Test void exhaustedRetriesAreTerminalAndAuditedOnce(){outcome(NotificationProviderResult.Status.TEMPORARY_FAILURE);UUID n=notification("EMAIL",false);process();dispatcher.processDue(OffsetDateTime.now().plusMinutes(1));dispatcher.processDue(OffsetDateTime.now().plusMinutes(3));assertThat(status(n)).isEqualTo("DEAD");dispatcher.processDue(OffsetDateTime.now().plusDays(1));verify(provider,times(3)).send(any());assertThat(audit("NOTIFICATION_RETRY_EXHAUSTED",deliveryId(n))).isEqualTo(1);}
 @Test void sentAuditIsOnceAndContainsNoDestination(){UUID n=notification("EMAIL",false);process();process();UUID id=deliveryId(n);assertThat(audit("NOTIFICATION_DELIVERED",id)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT metadata::text FROM audit.functional_audit_log WHERE action='NOTIFICATION_DELIVERED' AND resource_id=?",String.class,id)).doesNotContain(username,"@example.test","+593999000123","Body","token");}
 @Test void sendRunsWithoutDatabaseTransaction(){doAnswer(inv->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new NotificationProviderResult(NotificationProviderResult.Status.DELIVERED,"SANDBOX","projects/dev/messages/safe-id",false,null);}).when(provider).send(any());UUID n=notification("EMAIL",false);process();assertThat(status(n)).isEqualTo("DELIVERED");}
 @Test void inboxReadStateSurvivesDelivery(){UUID n=notification("EMAIL",false);assertThat(call("/"+n+"/read",HttpMethod.PATCH,username,null).getStatusCode()).isEqualTo(HttpStatus.OK);process();assertThat(jdbc.queryForObject("SELECT status FROM notification.notifications WHERE id=?",String.class,n)).isEqualTo("READ");assertThat(status(n)).isEqualTo("DELIVERED");}
 @ParameterizedTest @ValueSource(strings={"PENDING","TEMPORARY_FAILURE"}) void twoWorkersDoNotSendSameDelivery(String initial)throws Exception{
  UUID n=notification("EMAIL",false);jdbc.update("UPDATE notification.deliveries SET status=? WHERE notification_id=?",initial,n);AtomicInteger calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  doAnswer(inv->{calls.incrementAndGet();entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Worker timed out");return new NotificationProviderResult(NotificationProviderResult.Status.DELIVERED,"SANDBOX","safe-id",false,null);}).when(provider).send(any());
  try(var executor=Executors.newFixedThreadPool(2)){var first=executor.submit(this::process);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();var second=executor.submit(this::process);second.get(10,TimeUnit.SECONDS);release.countDown();first.get(10,TimeUnit.SECONDS);}
  assertThat(calls.get()).isEqualTo(1);assertThat(status(n)).isEqualTo("DELIVERED");
 }
 @Test void concurrentDeviceRegistrationDoesNotDuplicate()throws Exception{
  String token="sensitive-fixture-"+UUID.randomUUID();CountDownLatch start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{start.await();return settings.register(username,"WEB",token);});var b=pool.submit(()->{start.await();return settings.register(username,"WEB",token);});start.countDown();assertThat(a.get(10,TimeUnit.SECONDS).id()).isEqualTo(b.get(10,TimeUnit.SECONDS).id());}
  assertThat(settings.devices(username)).hasSize(1);
 }
 @Test void deviceDisabledDuringSendIsNotReactivated(){var d=device("WEB");doAnswer(inv->{settings.disable(username,d.id());return NotificationProviderResult.failure(NotificationProviderResult.Status.INVALID_DESTINATION,"SANDBOX",false,"INVALID_DESTINATION");}).when(provider).send(any());UUID n=notification("PUSH",false);process();assertThat(status(n)).isEqualTo("INVALID_DESTINATION");assertThat(settings.devices(username).getFirst().active()).isFalse();assertThat(audit("NOTIFICATION_DEVICE_DISABLED",d.id())).isEqualTo(1);assertThat(audit("NOTIFICATION_DESTINATION_DISABLED",d.id())).isZero();}
 @Test void staleProcessingRecoversWithStableIdempotencyReference(){UUID n=notification("EMAIL",false);UUID id=deliveryId(n);jdbc.update("UPDATE notification.deliveries SET status='PROCESSING',attempts=1,processing_token=?,last_attempt_at=? WHERE id=?",UUID.randomUUID(),OffsetDateTime.now().minusMinutes(10),id);dispatcher.recoverStale(OffsetDateTime.now());assertThat(status(n)).isEqualTo("TEMPORARY_FAILURE");dispatcher.processDue(OffsetDateTime.now().plusMinutes(1));assertThat(deliveryId(n)).isEqualTo(id);assertThat(status(n)).isEqualTo("DELIVERED");}
 @Test void freshProcessingIsNotRecovered(){UUID n=notification("EMAIL",false);jdbc.update("UPDATE notification.deliveries SET status='PROCESSING',attempts=1,processing_token=?,last_attempt_at=? WHERE notification_id=?",UUID.randomUUID(),OffsetDateTime.now(),n);dispatcher.recoverStale(OffsetDateTime.now());assertThat(status(n)).isEqualTo("PROCESSING");verify(provider,never()).send(any());}
 @ParameterizedTest @ValueSource(booleans={false,true}) void extensionSuppressesPendingReminderForPreviousEndAndAllowsNewEnd(boolean generationRaced){
  UUID vehicle=UUID.randomUUID(),zone=UUID.randomUUID(),street=UUID.randomUUID(),space=UUID.randomUUID(),session=UUID.randomUUID();
  var now=OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate) VALUES(?,?,?)",vehicle,owner,"CP24"+owner.toString().substring(0,6).toUpperCase(java.util.Locale.ROOT));
  jdbc.update("INSERT INTO parking.zones(id,code,name) VALUES(?,?,'Fixture')",zone,zone.toString());
  jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name) VALUES(?,?,?,'Fixture')",street,zone,street.toString());
  jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number) VALUES(?,?,?,?,'1')",space,street,space.toString(),space.toString());
  jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status) VALUES(?,?,?,?,?,?,'ACTIVE')",session,owner,vehicle,space,now.minusMinutes(50),now.plusMinutes(10));
  try{
   device("ANDROID");UUID r=rule("PUSH",false);
   jdbc.update("UPDATE configuration.notification_rules SET event_type='PARKING_ENDING_SOON' WHERE id=?",r);
   UUID source=NotificationEventIds.stable("PARKING_SESSION",session,"EXPIRATION",now.plusMinutes(10));
   if(generationRaced)jdbc.update("UPDATE parking.parking_sessions SET expected_end_at=?,status='EXTENDED' WHERE id=?",now.plusMinutes(40),session);
   generation.generate(owner,"PARKING_ENDING_SOON",source,null,"PARKING_SESSION",session,now,Map.of("minutesBefore",10,"minutesRemaining",10));
   UUID old=jdbc.queryForObject("SELECT id FROM notification.notifications WHERE source_event_id=? AND channel='PUSH'",UUID.class,source);
   if(!generationRaced)jdbc.update("UPDATE parking.parking_sessions SET expected_end_at=?,status='EXTENDED' WHERE id=?",now.plusMinutes(40),session);
   process();assertThat(status(old)).isEqualTo("SUPPRESSED");verify(provider,never()).send(any());
   UUID next=NotificationEventIds.stable("PARKING_SESSION",session,"EXPIRATION",now.plusMinutes(40));
   generation.generate(owner,"PARKING_ENDING_SOON",next,null,"PARKING_SESSION",session,now,Map.of("minutesBefore",10,"minutesRemaining",10));
   UUID current=jdbc.queryForObject("SELECT id FROM notification.notifications WHERE source_event_id=? AND channel='PUSH'",UUID.class,next);
   process();assertThat(status(current)).isEqualTo("DELIVERED");verify(provider,times(1)).send(any());
  }finally{
   jdbc.update("DELETE FROM parking.parking_sessions WHERE id=?",session);
   jdbc.update("DELETE FROM parking.parking_spaces WHERE id=?",space);jdbc.update("DELETE FROM parking.streets WHERE id=?",street);jdbc.update("DELETE FROM parking.zones WHERE id=?",zone);jdbc.update("DELETE FROM identity.vehicles WHERE id=?",vehicle);
  }
 }
 @Test void flywayValidatesCurrentSchema(){flyway.validate();assertThat(flyway.info().current().getVersion().toString()).isEqualTo("35");}

 @Test void approvedExtensionCreatesOneLogicalInboxItemAndPerDevicePushes(){
  device("ANDROID");device("IOS");UUID source=UUID.randomUUID();OffsetDateTime now=OffsetDateTime.now(); UUID outbox=outbox("PARKING_EXTENSION_CONFIRMED",source);
  int generated=generation.generate(owner,"PARKING_EXTENSION_CONFIRMED",source,outbox,
   "PARKING_SESSION",UUID.randomUUID(),now,Map.of("additionalMinutes",30,"newExpectedEndTime","13:30","amount","0.13","currency","USD"));
  assertThat(generated).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.inbox_items WHERE user_id=? AND event_type='PARKING_EXTENSION_CONFIRMED' AND source_event_id=?",Integer.class,owner,source)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE user_id=? AND notification_type='PARKING_EXTENSION_CONFIRMED' AND source_event_id=? AND channel='PUSH'",Integer.class,owner,source)).isEqualTo(1);
  UUID push=jdbc.queryForObject("SELECT id FROM notification.notifications WHERE user_id=? AND notification_type='PARKING_EXTENSION_CONFIRMED' AND source_event_id=? AND channel='PUSH'",UUID.class,owner,source);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.deliveries WHERE notification_id=?",Integer.class,push)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT message FROM notification.inbox_items WHERE user_id=? AND event_type='PARKING_EXTENSION_CONFIRMED' AND source_event_id=?",String.class,owner,source)).contains("30 minutos","13:30");
 }

 @Test void pushDispatcherCarriesOwnerAudienceGuardInDataOnlyRequest(){
  device("ANDROID");notification("PUSH",false);process();
  org.mockito.ArgumentCaptor<NotificationProviderRequest> request=org.mockito.ArgumentCaptor.forClass(NotificationProviderRequest.class);
  verify(provider).send(request.capture());
  assertThat(request.getValue().data()).containsEntry("recipientOwnerId",owner.toString());
  assertThat(request.getValue().data()).doesNotContainKey("title").doesNotContainKey("body");
 }

 @Test void pushPreferenceOffDoesNotCreatePushNotificationsOrDeliveries(){
  settings.preferences(username,Map.of("PUSH",false));device("ANDROID");UUID source=UUID.randomUUID();UUID outbox=outbox("PARKING_EXTENSION_CONFIRMED",source);
  generation.generate(owner,"PARKING_EXTENSION_CONFIRMED",source,outbox,"PARKING_SESSION",UUID.randomUUID(),OffsetDateTime.now(),Map.of("additionalMinutes",30,"newExpectedEndTime","13:30","amount","0.13","currency","USD"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE user_id=? AND notification_type='PARKING_EXTENSION_CONFIRMED' AND source_event_id=? AND channel='PUSH'",Integer.class,owner,source)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.deliveries d JOIN notification.notifications n ON n.id=d.notification_id WHERE n.user_id=? AND n.notification_type='PARKING_EXTENSION_CONFIRMED' AND n.source_event_id=? AND d.channel='PUSH'",Integer.class,owner,source)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.inbox_items WHERE user_id=? AND event_type='PARKING_EXTENSION_CONFIRMED' AND source_event_id=?",Integer.class,owner,source)).isEqualTo(1);
 }

 private UUID outbox(String type, UUID aggregateId) {
  UUID id=UUID.randomUUID();outboxes.add(id);
  jdbc.update("INSERT INTO audit.outbox_events(id,aggregate_type,aggregate_id,event_type,payload,status,occurred_at) VALUES(?,'PAYMENT',?,?,'{}','PENDING',CURRENT_TIMESTAMP)",id,aggregateId,type);
  return id;
 }

 @Test void repeatedEventCreatesOneNotificationAndDelivery(){
  UUID ruleId=rule("EMAIL",false),event=UUID.randomUUID();var rule=ruleRepository.findById(ruleId).orElseThrow();OffsetDateTime now=OffsetDateTime.now();
  assertThat(generation.createForRule(owner,rule,"PAYMENT_APPROVED",event,null,"PAYMENT",UUID.randomUUID(),now,Map.of())).isEqualTo(1);
  assertThat(generation.createForRule(owner,rule,"PAYMENT_APPROVED",event,null,"PAYMENT",UUID.randomUUID(),now,Map.of())).isZero();process();
  UUID n=jdbc.queryForObject("SELECT id FROM notification.notifications WHERE source_event_id=?",UUID.class,event);
  assertThat(deliveries(n)).isEqualTo(1);verify(provider,times(1)).send(any());
 }
 @Test void failingDeliveryDoesNotStopBatch(){
  doAnswer(inv->{NotificationProviderRequest request=inv.getArgument(0);return "EMAIL".equals(request.channel())?NotificationProviderResult.failure(NotificationProviderResult.Status.PERMANENT_FAILURE,"SANDBOX",false,"PERMANENT_FAILURE"):new NotificationProviderResult(NotificationProviderResult.Status.DELIVERED,"SANDBOX","safe-id",false,null);}).when(provider).send(any());
  UUID first=notification("EMAIL",false),second=notification("WHATSAPP",false);process();assertThat(status(first)).isEqualTo("PERMANENT_FAILURE");assertThat(status(second)).isEqualTo("DELIVERED");
 }
 @Test void unconfiguredProviderUsesControlledBoundedRetry(){doReturn("UNCONFIGURED").when(registry).configuredCode("EMAIL");UUID n=notification("EMAIL",false);jdbc.update("UPDATE notification.deliveries SET provider_code='UNCONFIGURED' WHERE notification_id=?",n);process();assertThat(status(n)).isEqualTo("TEMPORARY_FAILURE");assertThat(jdbc.queryForObject("SELECT error_code FROM notification.deliveries WHERE notification_id=?",String.class,n)).isEqualTo("PROVIDER_NOT_CONFIGURED");verify(provider,never()).send(any());}
 @Test void unknownProviderNeverDelivers(){UUID n=notification("EMAIL",false);jdbc.update("UPDATE notification.deliveries SET provider_code='UNKNOWN_PROVIDER' WHERE notification_id=?",n);process();assertThat(status(n)).isEqualTo("PERMANENT_FAILURE");verify(provider,never()).send(any());}
 @Test void unsafeStaleProcessingRequiresReview(){UUID n=notification("EMAIL",false);jdbc.update("UPDATE notification.deliveries SET provider_code='UNCONFIGURED',status='PROCESSING',attempts=1,last_attempt_at=? WHERE notification_id=?",OffsetDateTime.now().minusMinutes(10),n);dispatcher.recoverStale(OffsetDateTime.now());assertThat(status(n)).isEqualTo("UNKNOWN");process();verify(provider,never()).send(any());assertThat(audit("NOTIFICATION_DELIVERY_REVIEW_REQUIRED",deliveryId(n))).isEqualTo(1);}
 @Test void preferenceChangedDuringRetryPreventsAnotherSend(){outcome(NotificationProviderResult.Status.TEMPORARY_FAILURE);UUID n=notification("EMAIL",false);process();settings.preferences(username,Map.of("EMAIL",false));dispatcher.processDue(OffsetDateTime.now().plusMinutes(2));assertThat(status(n)).isEqualTo("SUPPRESSED");verify(provider,times(1)).send(any());}
 @Test void disabledDeviceIsNotSent(){var d=device("WEB");UUID n=notification("PUSH",false);settings.disable(username,d.id());process();assertThat(status(n)).isEqualTo("NO_DESTINATION");verify(provider,never()).send(any());}
 @Test void concurrentPreparationDoesNotDuplicateDelivery()throws Exception{UUID n=notification("EMAIL",false);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->dispatcher.prepare(n));var b=pool.submit(()->dispatcher.prepare(n));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}assertThat(deliveries(n)).isEqualTo(1);process();verify(provider,times(1)).send(any());}
 @Test void invalidRegistrationIsControlledWithoutLeakingToken(){String token="SECRET_WITH_CONTROL\n";var response=call("/devices",HttpMethod.POST,username,Map.of("platform","WEB","token",token));assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(response.getBody()).doesNotContain(token,"SECRET_WITH_CONTROL");}

 @Test void configuringUnsentProviderAllowsSameDeliveryToProceed(){
  doReturn("UNCONFIGURED").when(registry).configuredCode("EMAIL");UUID n=notification("EMAIL",false);process();UUID id=deliveryId(n);assertThat(status(n)).isEqualTo("TEMPORARY_FAILURE");
  doReturn("SANDBOX").when(registry).configuredCode("EMAIL");dispatcher.processDue(OffsetDateTime.now().plusMinutes(1));assertThat(status(n)).isEqualTo("DELIVERED");assertThat(deliveryId(n)).isEqualTo(id);verify(provider,times(1)).send(any());
 }
 @Test void legacyUnsentNotificationGetsDeliveryWithoutLosingAttempts(){
  UUID n=notification("EMAIL",false);jdbc.update("DELETE FROM notification.deliveries WHERE notification_id=?",n);
  jdbc.update("UPDATE notification.notifications SET status='FAILED',attempt_count=1,failure_reason='PROVIDER_NOT_CONFIGURED' WHERE id=?",n);process();assertThat(status(n)).isEqualTo("DELIVERED");assertThat(jdbc.queryForObject("SELECT attempts FROM notification.deliveries WHERE notification_id=?",Integer.class,n)).isEqualTo(2);process();assertThat(deliveries(n)).isEqualTo(1);verify(provider,times(1)).send(any());
 }
 @Test void legacyUncertainAttemptIsNotResent(){
  UUID n=notification("EMAIL",false);jdbc.update("DELETE FROM notification.deliveries WHERE notification_id=?",n);
  jdbc.update("UPDATE notification.notifications SET status='FAILED',attempt_count=1,failure_reason='DELIVERY_FAILED' WHERE id=?",n);process();assertThat(status(n)).isEqualTo("UNKNOWN");process();assertThat(deliveries(n)).isEqualTo(1);verify(provider,never()).send(any());assertThat(audit("NOTIFICATION_DELIVERY_REVIEW_REQUIRED",deliveryId(n))).isEqualTo(1);
 }
 @Test void legacyRetryLimitIsNotReset(){
  UUID n=notification("EMAIL",false);jdbc.update("DELETE FROM notification.deliveries WHERE notification_id=?",n);
  jdbc.update("UPDATE notification.notifications SET status='FAILED',attempt_count=3,failure_reason='PROVIDER_NOT_CONFIGURED' WHERE id=?",n);process();assertThat(status(n)).isEqualTo("DEAD");verify(provider,never()).send(any());
 }
}
