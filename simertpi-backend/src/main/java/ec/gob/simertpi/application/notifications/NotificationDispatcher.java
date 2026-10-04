package ec.gob.simertpi.application.notifications;
import ec.gob.simertpi.application.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.OffsetDateTime;
import java.util.*;
@Service
public class NotificationDispatcher {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ec.gob.simertpi.application.operations.OperationalMetrics metrics;
    private void metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event event) { if(metrics!=null)metrics.event(event); }

 private final JdbcTemplate jdbc; private final TransactionTemplate tx;
 private final NotificationProviderRegistry registry; private final NotificationRetryPolicy retry; private final AuditService audit;
 public NotificationDispatcher(JdbcTemplate jdbc,PlatformTransactionManager manager,NotificationProviderRegistry registry,NotificationRetryPolicy retry,AuditService audit){
  this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.registry=registry;this.retry=retry;this.audit=audit;
 }
 // Generation queues locally in its existing transaction; never sends externally here.
 public void prepare(UUID id){tx.executeWithoutResult(s->{
  var list=jdbc.queryForList("SELECT n.*,u.enabled AS user_enabled,u.email,u.phone FROM notification.notifications n JOIN identity.users u ON u.id=n.user_id WHERE n.id=?",id);
  if(list.isEmpty())return;var n=list.getFirst();String channel=(String)n.get("channel");
  if(!Set.of("PUSH","WHATSAPP","EMAIL").contains(channel))return;
  List<Destination> destinations=destinations(n);
  if(destinations.isEmpty())destinations=List.of(new Destination("NONE",null,null));
  for(var d:destinations)jdbc.update("INSERT INTO notification.deliveries(id,notification_id,channel,destination_reference,device_id,provider_code,status) VALUES (?,?,?,?,?,?,'PENDING') ON CONFLICT(notification_id,channel,destination_reference) DO NOTHING",UUID.randomUUID(),id,channel,d.reference(),d.device(),registry.configuredCode(channel));
 });}
 private List<Destination> destinations(Map<String,Object> n){
  if(!Boolean.TRUE.equals(n.get("user_enabled")))return List.of();
  String channel=(String)n.get("channel");
  if("PUSH".equals(channel))return jdbc.query("SELECT id,token FROM notification.devices WHERE user_id=? AND active=true ORDER BY id",(rs,i)->new Destination("DEVICE:"+rs.getObject("id"),rs.getObject("id",UUID.class),rs.getString("token")),n.get("user_id"));
  String value=(String)n.get("EMAIL".equals(channel)?"email":"phone");
  boolean valid=value!=null&&("EMAIL".equals(channel)?value.length()<=254&&value.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+") : value.matches("\\+[1-9][0-9]{7,14}"));
  return valid?List.of(new Destination(channel+":"+NotificationSettingsService.hash(value),null,value)):List.of();
 }
 private boolean permitted(Map<String,Object> n){
  UUID rule=(UUID)n.get("rule_id");
  if(rule==null)return false;
  var rules=jdbc.queryForList("SELECT enabled,mandatory,valid_from,valid_to,channel,event_type FROM configuration.notification_rules WHERE id=?",rule);
  if(rules.isEmpty()||!Boolean.TRUE.equals(rules.getFirst().get("enabled")))return false;
  var r=rules.getFirst();
  if(!n.get("channel").equals(r.get("channel"))||!n.get("notification_type").equals(r.get("event_type")))return false;
  OffsetDateTime now=OffsetDateTime.now();
  if(((java.sql.Timestamp)r.get("valid_from")).toInstant().isAfter(now.toInstant())||r.get("valid_to")!=null&& !((java.sql.Timestamp)r.get("valid_to")).toInstant().isAfter(now.toInstant()))return false;
  return Boolean.TRUE.equals(r.get("mandatory"))||Boolean.TRUE.equals(jdbc.query("SELECT enabled FROM notification.preferences WHERE user_id=? AND channel=?",rs->rs.next()?rs.getBoolean(1):false,n.get("user_id"),n.get("channel")));
 }
 private record Destination(String reference,UUID device,String value) { @Override public String toString(){return "Destination[redacted]";} }
 private record Claim(UUID id,UUID token,String provider,int attempts,UUID device,NotificationProviderRequest request) {}
 public void processDue(OffsetDateTime now){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Dispatcher must run outside caller transaction");
  adoptLegacyPending(now);
  recoverStale(now);
  for(int i=0;i<100;i++){
   Claim claim=tx.execute(s->claim(now));
   if(claim==null)return;
   if(claim.request()==null)continue;
   NotificationProviderResult result;
   try{result=registry.send(claim.provider(),claim.request());}catch(RuntimeException failure){result=null;}
   NotificationProviderResult outcome=result;
   tx.executeWithoutResult(s->complete(claim,outcome,now));
  }
 }
 // Adopt only unread, due records from the previous model. Never infer delivery success.
 private void adoptLegacyPending(OffsetDateTime now){tx.executeWithoutResult(s->{
  var candidates=jdbc.queryForList("""
   SELECT n.id,n.channel,n.attempt_count,n.failure_reason FROM notification.notifications n
   WHERE n.status IN ('PENDING','FAILED') AND n.rule_id IS NOT NULL
    AND (n.next_attempt_at IS NULL OR n.next_attempt_at<=?)
    AND n.channel IN ('PUSH','WHATSAPP','EMAIL')
    AND NOT EXISTS(SELECT 1 FROM notification.deliveries d WHERE d.notification_id=n.id)
   ORDER BY n.created_at,n.id LIMIT 100
   """,now);
  for(var n:candidates){
   UUID id=(UUID)n.get("id");int attempts=((Number)n.get("attempt_count")).intValue();
   if(attempts==0||"PROVIDER_NOT_CONFIGURED".equals(n.get("failure_reason"))){
    prepare(id);
    jdbc.update("UPDATE notification.deliveries SET attempts=? WHERE notification_id=? AND status='PENDING' AND attempts=0",attempts,id);
   }else{
    UUID delivery=UUID.randomUUID();
    int inserted=jdbc.update("INSERT INTO notification.deliveries(id,notification_id,channel,destination_reference,provider_code,status,attempts,error_code) VALUES (?,?,?,'LEGACY','UNCONFIGURED','UNKNOWN',?,'LEGACY_DELIVERY_UNCERTAIN') ON CONFLICT(notification_id,channel,destination_reference) DO NOTHING",delivery,id,n.get("channel"),attempts);
    if(inserted==1){auditDelivery("NOTIFICATION_DELIVERY_REVIEW_REQUIRED",delivery,(String)n.get("channel"),"UNKNOWN");aggregate(id);}
   }
  }
 });}
 private Claim claim(OffsetDateTime now){
  var rows=jdbc.queryForList("SELECT * FROM notification.deliveries WHERE status IN ('PENDING','TEMPORARY_FAILURE') AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",now);
  if(rows.isEmpty())return null;var row=rows.getFirst();UUID id=(UUID)row.get("id");
  var n=jdbc.queryForMap("SELECT n.*,u.enabled AS user_enabled,u.email,u.phone FROM notification.notifications n JOIN identity.users u ON u.id=n.user_id WHERE n.id=?",row.get("notification_id"));
  String terminal=null;Destination destination=null;
  if(!permitted(n))terminal="SUPPRESSED";
  else destination=destinations(n).stream().filter(d->d.reference().equals(row.get("destination_reference"))).findFirst().orElse(null);
  if(terminal==null&&destination==null)terminal="NO_DESTINATION";
  int attempts=((Number)row.get("attempts")).intValue();
  if(terminal==null&&attempts>=retry.maximumAttempts())terminal="DEAD";
  if(terminal!=null){
   jdbc.update("UPDATE notification.deliveries SET status=?,error_code=?,next_attempt_at=null,updated_at=? WHERE id=?",terminal,terminal,now,id);
   if("DEAD".equals(terminal))auditDelivery("NOTIFICATION_RETRY_EXHAUSTED",id,(String)row.get("channel"),terminal);
   if("NO_DESTINATION".equals(terminal))metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event.NOTIFICATION_NO_DESTINATION);
   aggregate((UUID)row.get("notification_id"));return new Claim(id,null,null,attempts,null,null);
  }
  String providerCode=(String)row.get("provider_code");
  // UNCONFIGURED never called an external provider, so resolving updated configuration is safe.
  if("UNCONFIGURED".equals(providerCode))providerCode=registry.configuredCode((String)row.get("channel"));
  if(attempts>0)metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event.NOTIFICATION_RETRY);
  UUID token=UUID.randomUUID();attempts++;
  jdbc.update("UPDATE notification.deliveries SET status='PROCESSING',provider_code=?,processing_token=?,attempts=?,last_attempt_at=?,next_attempt_at=null,updated_at=? WHERE id=?",providerCode,token,attempts,now,now,id);
  return new Claim(id,token,providerCode,attempts,destination.device(),new NotificationProviderRequest(id,(UUID)row.get("notification_id"),(String)row.get("channel"),destination.value(),(String)n.get("title"),(String)n.get("message")));
 }
 private void complete(Claim c,NotificationProviderResult result,OffsetDateTime now){
  var rows=jdbc.queryForList("SELECT notification_id FROM notification.deliveries WHERE id=? AND status='PROCESSING' AND processing_token=? FOR UPDATE",c.id(),c.token());
  if(rows.isEmpty())return;
  String status="UNKNOWN",error="UNKNOWN";String external=null;OffsetDateTime next=null;
  if(result!=null&&result.status()!=null&&c.provider().equals(result.providerCode())){
   status=result.status().name();error=status;
   if("DELIVERED".equals(status)){
    if(result.externalMessageId()!=null&&!result.externalMessageId().matches("[A-Za-z0-9._:-]{1,150}")){status="UNKNOWN";error="UNKNOWN";}
    else{external=result.externalMessageId();error=null;}
   }
   if("TEMPORARY_FAILURE".equals(status)){
    if(!result.retryable()){status="PERMANENT_FAILURE";}
    else if(c.attempts()>=retry.maximumAttempts()){status="DEAD";}
    else next=retry.nextAttempt(now,c.attempts());
   }
   if(Set.of("PROVIDER_NOT_CONFIGURED","PROVIDER_UNKNOWN","SANDBOX_DISABLED").contains(result.errorCode()==null?"":result.errorCode()))error=result.errorCode();
  }
  jdbc.update("UPDATE notification.deliveries SET status=?,error_code=?,external_message_id=?,next_attempt_at=?,delivered_at=?,processing_token=null,updated_at=? WHERE id=?",status,error,external,next,"DELIVERED".equals(status)?now:null,now,c.id());
  if(result!=null&&result.status()==NotificationProviderResult.Status.TEMPORARY_FAILURE&&result.retryable()&&c.provider().equals(result.providerCode()))metric(ec.gob.simertpi.application.operations.OperationalMetrics.Event.NOTIFICATION_TEMPORARY_FAILURE);
  if("INVALID_DESTINATION".equals(status)&&c.device()!=null){
   int disabled=jdbc.update("UPDATE notification.devices SET active=false,disabled_at=?,updated_at=? WHERE id=? AND active=true",now,now,c.device());
   if(disabled==1)audit.success("NOTIFICATION_DESTINATION_DISABLED","NOTIFICATION_DEVICE",c.device(),null,Map.of("destinationType","DEVICE"));
  }
  String action=switch(status){case "DELIVERED"->"NOTIFICATION_DELIVERED";case "PERMANENT_FAILURE","INVALID_DESTINATION"->"NOTIFICATION_PERMANENT_FAILURE";case "DEAD"->"NOTIFICATION_RETRY_EXHAUSTED";case "UNKNOWN"->"NOTIFICATION_DELIVERY_REVIEW_REQUIRED";default->null;};
  if(action!=null)auditDelivery(action,c.id(),c.request().channel(),status);
  aggregate((UUID)rows.getFirst().get("notification_id"));
 }
 private void auditDelivery(String action,UUID id,String channel,String status){
  audit.recordOutcome(action,"NOTIFICATION_DELIVERY",id,"DELIVERED".equals(status)?"SUCCESS":"FAILURE",Map.of("channel",channel,"status",status,"notificationId",jdbc.queryForObject("SELECT notification_id FROM notification.deliveries WHERE id=?",UUID.class,id),"providerCode",jdbc.queryForObject("SELECT provider_code FROM notification.deliveries WHERE id=?",String.class,id)));
 }
 private void aggregate(UUID id){
  // Preserve READ and read_at: inbox consumption is independent from external delivery.
  jdbc.update("""
   UPDATE notification.notifications n SET
    status=CASE WHEN n.status='READ' THEN 'READ'
      WHEN EXISTS(SELECT 1 FROM notification.deliveries d WHERE d.notification_id=n.id AND d.status='DELIVERED') THEN 'SENT'
      WHEN EXISTS(SELECT 1 FROM notification.deliveries d WHERE d.notification_id=n.id AND d.status IN ('PENDING','PROCESSING','TEMPORARY_FAILURE')) THEN 'PENDING' ELSE 'FAILED' END,
    sent_at=(SELECT min(delivered_at) FROM notification.deliveries d WHERE d.notification_id=n.id),
    failure_reason=(SELECT error_code FROM notification.deliveries d WHERE d.notification_id=n.id AND error_code IS NOT NULL ORDER BY created_at,id LIMIT 1),
    attempt_count=(SELECT coalesce(sum(attempts),0) FROM notification.deliveries d WHERE d.notification_id=n.id),
    next_attempt_at=null,recipient=null,updated_at=CURRENT_TIMESTAMP WHERE n.id=?
   """,id);
 }
 public void recoverStale(OffsetDateTime now){tx.executeWithoutResult(s->{
  var rows=jdbc.queryForList("SELECT * FROM notification.deliveries WHERE status='PROCESSING' AND last_attempt_at<=? FOR UPDATE SKIP LOCKED",retry.staleBefore(now));
  for(var row:rows){
   String channel=(String)row.get("channel"),code=(String)row.get("provider_code");
   boolean safe=registry.resolve(channel,code).map(NotificationProvider::supportsIdempotency).orElse(false);
   int attempts=((Number)row.get("attempts")).intValue();String status=safe?(attempts>=retry.maximumAttempts()?"DEAD":"TEMPORARY_FAILURE"):"UNKNOWN";
   jdbc.update("UPDATE notification.deliveries SET status=?,error_code='STALE_PROCESSING',processing_token=null,next_attempt_at=?,updated_at=? WHERE id=?",status,"TEMPORARY_FAILURE".equals(status)?retry.nextAttempt(now,attempts):null,now,row.get("id"));
   if("DEAD".equals(status)||"UNKNOWN".equals(status))auditDelivery("DEAD".equals(status)?"NOTIFICATION_RETRY_EXHAUSTED":"NOTIFICATION_DELIVERY_REVIEW_REQUIRED",(UUID)row.get("id"),channel,status);
   aggregate((UUID)row.get("notification_id"));
  }
 });}
}
