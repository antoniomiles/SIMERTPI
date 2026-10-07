package ec.gob.simertpi.api.citizen;

import ec.gob.simertpi.application.citizen.CitizenActivityService;
import ec.gob.simertpi.application.notifications.*;
import ec.gob.simertpi.domain.parking.repository.ParkingControlEventRepository;
import ec.gob.simertpi.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.hikari.maximum-pool-size=2")
@AutoConfigureMockMvc
@Transactional
class CitizenActivityIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CitizenActivityService service;
    @Autowired NotificationGenerationService generation;
    @Autowired NotificationReminderScheduler reminders;
    @Autowired ParkingControlEventRepository control;
    @Autowired MockMvc mvc;
    UUID owner, other, vehicle, space, session;
    String name, otherName;
    OffsetDateTime start, end;
    @BeforeEach void fixture() {
        owner=UUID.randomUUID();other=UUID.randomUUID();vehicle=UUID.randomUUID();space=UUID.randomUUID();session=UUID.randomUUID();
        name="history-"+owner;otherName="other-"+other;
        for(var entry:Map.of(owner,name,other,otherName).entrySet()) {
            jdbc.update("INSERT INTO identity.users(id,username,email,password_hash,first_name,last_name,phone) VALUES(?,?,?,'test-placeholder','Ana','Ciudadana','0990000000')",
                    entry.getKey(),entry.getValue(),entry.getValue()+"@example.test");
        }
        UUID zone=UUID.randomUUID(),street=UUID.randomUUID();
        jdbc.update("INSERT INTO identity.vehicles(id,user_id,plate) VALUES(?,?,'ABC1234')",vehicle,owner);
        jdbc.update("INSERT INTO parking.zones(id,code,name) VALUES(?,?,'Zona de prueba')",zone,zone.toString());
        jdbc.update("INSERT INTO parking.streets(id,zone_id,code,name) VALUES(?,?,?,'Calle de prueba')",street,zone,street.toString());
        jdbc.update("INSERT INTO parking.parking_spaces(id,street_id,code,qr_code,space_number) VALUES(?,?,? ,?,'1')",space,street,space.toString(),"qr-"+space);
        start=OffsetDateTime.now().minusHours(2);end=start.plusMinutes(90);
        jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,ended_at,status,total_amount) VALUES(?,?,?,?,?,?,?,'COMPLETED',0.25)",session,owner,vehicle,space,start,end,end.plusMinutes(2));
    }
    UUID payment(String status,String amount) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO payments.payments(id,parking_session_id,provider,idempotency_key,amount,currency,status,paid_at) VALUES(?,?,'INTERNAL',?,?::numeric,'USD',?,?)",id,session,id.toString(),amount,status,end);
        return id;
    }
    @Test void historySumsApprovedPaymentsOnceIncludingExtensionsAndPreservesInactivePlate() {
        payment("APPROVED","0.25");UUID extensionPayment=payment("APPROVED","0.13");payment("DECLINED","10.00");payment("FAILED","20.00");
        jdbc.update("INSERT INTO payments.payment_attempts(id,payment_id,attempt_number,status) VALUES(?,?,1,'APPROVED')",UUID.randomUUID(),extensionPayment);
        jdbc.update("INSERT INTO parking.session_extensions(id,parking_session_id,payment_id,additional_minutes,previous_expected_end_at,new_expected_end_at,amount,status) VALUES(?,?,?,30,?,?,0.13,'APPROVED')",UUID.randomUUID(),session,extensionPayment,start.plusMinutes(60),end);
        jdbc.update("UPDATE identity.vehicles SET active=false WHERE id=?",vehicle);
        var detail=service.historyDetail(name,session);
        assertThat(detail.session().plate()).isEqualTo("ABC1234");
        assertThat(detail.session().contractedMinutes()).isEqualTo(90);
        assertThat(detail.session().occupiedMinutes()).isEqualTo(92);
        assertThat(detail.session().paidAmounts()).containsExactly(new CitizenActivityService.Money("0.38","USD"));
        assertThat(detail.extensions()).hasSize(1);
    }
    @Test void terminalOnlyHistoryIncludesCancelledButNotUnpaidOrOpenSessions() {
        for(String status:List.of("ACTIVE","EXTENDED","EXPIRED","MAX_TIME_REACHED","PENDING_PAYMENT")) {
            jdbc.update("UPDATE parking.parking_sessions SET status=? WHERE id=?",status,session);
            assertThat(service.history(name,20,0).items()).isEmpty();
        }
        jdbc.update("UPDATE parking.parking_sessions SET status='CANCELLED' WHERE id=?",session);
        assertThat(service.history(name,20,0).items()).hasSize(1);
        assertThat(service.history(name,20,0).items().getFirst().paidAmounts()).isEmpty();
    }
    @Test void historyPaginationHasStableTieOrder() {
        List<UUID> ids=new ArrayList<>(List.of(session));
        for(int i=0;i<3;i++) {
            UUID id=UUID.randomUUID();ids.add(id);
            jdbc.update("INSERT INTO parking.parking_sessions(id,user_id,vehicle_id,parking_space_id,started_at,expected_end_at,status) VALUES(?,?,?,?,?,?,'CANCELLED')",id,owner,vehicle,space,start,end);
        }
        var first=service.history(name,2,0);var second=service.history(name,2,2);
        assertThat(first.hasMore()).isTrue();assertThat(second.hasMore()).isFalse();
        assertThat(first.items()).extracting(CitizenActivityService.History::id).doesNotContainAnyElementsOf(second.items().stream().map(CitizenActivityService.History::id).toList());
        var expected=jdbc.queryForList("SELECT id FROM parking.parking_sessions WHERE user_id=? ORDER BY started_at DESC,id DESC",UUID.class,owner);
        assertThat(first.items().stream().map(CitizenActivityService.History::id).toList()).isEqualTo(expected.subList(0,2));
    }
    @Test void historyOwnershipAndMultiRoleNeverExpandToGlobal() throws Exception {
        mvc.perform(get("/api/v1/citizen/history").with(user(otherName).authorities(()->"CITIZEN",()->"SIMERTPI_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/citizen/history/"+session).with(user(otherName).authorities(()->"CITIZEN",()->"INSPECTOR"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/citizen/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/citizen/history").with(user(name).authorities(()->"INSPECTOR"))).andExpect(status().isForbidden());
    }
    @Test void profileContainsOnlyOwnReadOnlyCitizenFields() throws Exception {
        mvc.perform(get("/api/v1/citizen/profile").with(user(name).authorities(()->"CITIZEN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Ana"))
                .andExpect(jsonPath("$.lastName").value("Ciudadana")).andExpect(jsonPath("$.username").value(name))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.enabled").doesNotExist());
    }
    UUID envelope(UUID source,String channel,String type) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO notification.notifications(id,user_id,source_event_id,notification_type,channel,title,message) VALUES(?,?,?,?,?,'Aviso ciudadano','Mensaje ciudadano')",id,owner,source,type,channel);
        return id;
    }
    @Test void logicalInboxDeduplicatesChannelsAndHasIndependentIdempotentReadState() {
        UUID source=UUID.randomUUID();
        List<UUID> envelopes=List.of(envelope(source,"PUSH","PAYMENT_APPROVED"),envelope(source,"EMAIL","PAYMENT_APPROVED"),envelope(source,"IN_APP","PAYMENT_APPROVED"));
        UUID delivery=UUID.randomUUID();
        jdbc.update("INSERT INTO notification.deliveries(id,notification_id,channel,destination_reference,provider_code,status) VALUES(?,?,'PUSH','NONE','UNCONFIGURED','PENDING')",delivery,envelopes.getFirst());
        assertThat(service.inbox(name,20,0).items()).hasSize(1);
        assertThat(service.unread(name).unreadCount()).isEqualTo(1);
        UUID logical=service.inbox(name,20,0).items().getFirst().id();
        var read=service.read(name,logical);assertThat(read.readAt()).isNotNull();
        assertThat(service.read(name,logical).readAt()).isEqualTo(read.readAt());
        assertThat(service.unread(name).unreadCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification.deliveries WHERE id=?",String.class,delivery)).isEqualTo("PENDING");
        for(UUID id:envelopes)assertThat(jdbc.queryForObject("SELECT status FROM notification.notifications WHERE id=?",String.class,id)).isEqualTo("PENDING");
    }
    @Test void logicalReadAndDetailRejectOtherOwners() throws Exception {
        envelope(UUID.randomUUID(),"IN_APP","PAYMENT_APPROVED");UUID id=service.inbox(name,20,0).items().getFirst().id();
        mvc.perform(patch("/api/v1/notifications/inbox/"+id+"/read").with(user(otherName).authorities(()->"CITIZEN"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications/inbox/"+id).with(user(otherName).authorities(()->"CITIZEN"))).andExpect(status().isNotFound());
        assertThat(service.unread(otherName).unreadCount()).isZero();
    }
    @Test void inboxPaginationAndUnreadCountDoNotDownloadAllOrExposeInternalFields() throws Exception {
        for(int i=0;i<4;i++)envelope(UUID.randomUUID(),"IN_APP","PAYMENT_APPROVED");
        assertThat(service.inbox(name,2,0).hasMore()).isTrue();assertThat(service.inbox(name,2,2).hasMore()).isFalse();
        mvc.perform(get("/api/v1/notifications/unread-count").with(user(name).authorities(()->"CITIZEN"))).andExpect(jsonPath("$.unreadCount").value(4));
        mvc.perform(get("/api/v1/notifications/inbox").with(user(name).authorities(()->"CITIZEN")))
                .andExpect(jsonPath("$.items[0].channel").doesNotExist()).andExpect(jsonPath("$.items[0].sourceEventId").doesNotExist())
                .andExpect(jsonPath("$.items[0].eventType").doesNotExist()).andExpect(jsonPath("$.items[0].referenceId").doesNotExist());
    }
    @Test void paginationLimitsAreEnforced() {
        assertThatThrownBy(()->service.history(name,101,0)).isInstanceOf(ec.gob.simertpi.api.InvalidRequestException.class);
        assertThatThrownBy(()->service.inbox(name,0,0)).isInstanceOf(ec.gob.simertpi.api.InvalidRequestException.class);
        assertThatThrownBy(()->service.inbox(name,20,-1)).isInstanceOf(ec.gob.simertpi.api.InvalidRequestException.class);
    }
    @Test void automaticLegacyAmonestacionAndExcessAreNotHumanWarningsOrCitizenSpam() {
        for(String type:List.of("AMONESTACION","GRACE_PERIOD","EXCESS_11_30","EXCESS_31_60","EXCESS_61_120","EXCESS_OVER_120"))envelope(UUID.randomUUID(),"PUSH",type);
        assertThat(service.inbox(name,20,0).items()).isEmpty();
        envelope(UUID.randomUUID(),"IN_APP","VERBAL_WARNING");
        assertThat(service.inbox(name,20,0).items()).hasSize(1);
    }
    @Test void expirationAndWarningAreDistinctAndSuccessiveExpirationsHaveNewIdentity() {
        var first=NotificationEventIds.stable("PARKING_SESSION",session,"EXPIRATION",end);
        var expired=NotificationEventIds.stable("PARKING_SESSION",session,"EXPIRATION",end);
        var next=NotificationEventIds.stable("PARKING_SESSION",session,"EXPIRATION",end.plusHours(1));
        assertThat(first).isEqualTo(expired).isNotEqualTo(next);
        envelope(first,"IN_APP","PARKING_ENDING_SOON");
        envelope(expired,"IN_APP","PARKING_TIME_EXPIRED");
        envelope(next,"IN_APP","PARKING_ENDING_SOON");
        assertThat(service.inbox(name,20,0).items()).hasSize(3);
    }
    @Test void controlDeduplicationIsPerContractualExpiration() {
        assertThat(control.insertIfAbsent(UUID.randomUUID(),session,owner,vehicle,space,"EXPIRATION",end,0)).isEqualTo(1);
        assertThat(control.insertIfAbsent(UUID.randomUUID(),session,owner,vehicle,space,"EXPIRATION",end,0)).isZero();
        jdbc.update("UPDATE parking.parking_sessions SET expected_end_at=? WHERE id=?",end.plusHours(1),session);
        assertThat(control.insertIfAbsent(UUID.randomUUID(),session,owner,vehicle,space,"EXPIRATION",end.plusHours(1),0)).isEqualTo(1);
    }
    @Test void inAppGenerationNeedsNoProviderAndKeepsGraceDurationPrivate() {
        generation.generate(owner,"PARKING_TIME_EXPIRED",UUID.randomUUID(),null,"PARKING_SESSION",session,end,Map.of("graceMinutes",10));
        generation.generate(owner,"PARKING_GRACE_EXCEEDED",UUID.randomUUID(),null,"PARKING_SESSION",session,end,Map.of("graceMinutes",10));
        var items=service.inbox(name,20,0).items();assertThat(items).hasSize(2);
        assertThat(items).extracting(CitizenActivityService.InboxItem::message).allSatisfy(text->assertThat(text).doesNotContain("10","hasta","inspector"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.deliveries d JOIN notification.notifications n ON n.id=d.notification_id WHERE n.user_id=?",Integer.class,owner)).isZero();
    }
    @Test void preferencesRemainOwnAndDoNotAllowInAppOptOut() throws Exception {
        mvc.perform(put("/api/v1/notifications/preferences").with(user(name).authorities(()->"CITIZEN"))
                .contentType("application/json").content("{\"PUSH\":true,\"EMAIL\":false}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification.preferences WHERE user_id=? AND channel='PUSH'",Boolean.class,owner)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.preferences WHERE user_id=?",Integer.class,other)).isZero();
        mvc.perform(put("/api/v1/notifications/preferences").with(user(name).authorities(()->"CITIZEN"))
                .contentType("application/json").content("{\"IN_APP\":false}"))
                .andExpect(status().isBadRequest());
    }
}
