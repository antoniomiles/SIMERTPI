package ec.gob.simertpi.application.notifications;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.api.ResourceNotFoundException;
import ec.gob.simertpi.domain.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import ec.gob.simertpi.api.InvalidRequestException;
import java.util.*;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Service
public class NotificationSettingsService {
 private final JdbcTemplate jdbc; private final UserRepository users; private final AuditService audit;
 public NotificationSettingsService(JdbcTemplate jdbc,UserRepository users,AuditService audit){this.jdbc=jdbc;this.users=users;this.audit=audit;}
 public record DeviceView(UUID id,String platform,boolean active,OffsetDateTime lastSeenAt,String tokenMasked) {}
 public record PreferenceView(String channel,boolean enabled) {}
 public UUID owner(String username){return users.findByUsername(username).filter(u->u.isEnabled()).orElseThrow(()->new ResourceNotFoundException("User not found")).getId();}
 public static String hash(String value){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
 }
 @Transactional public DeviceView register(String username,String platform,String token){
  UUID user=owner(username);
  if(platform==null||!Set.of("ANDROID","IOS","WEB").contains(platform)||token==null||token.isBlank()||token.length()>4096||token.chars().anyMatch(Character::isISOControl))
   throw new InvalidRequestException("Invalid device registration");
  String hash=hash(token);
  jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",hash);
  var existing=jdbc.queryForList("SELECT id,user_id,platform,active FROM notification.devices WHERE token_hash=? FOR UPDATE",hash);
  UUID id;boolean changed;
  if(existing.isEmpty()){
   id=UUID.randomUUID();changed=true;
   jdbc.update("INSERT INTO notification.devices(id,user_id,platform,token,token_hash) VALUES (?,?,?,?,?)",id,user,platform,token,hash);
  }else{
   var row=existing.getFirst();id=(UUID)row.get("id");
   if(!user.equals(row.get("user_id"))||!platform.equals(row.get("platform"))) throw new IllegalArgumentException("Device registration conflict");
   changed=!Boolean.TRUE.equals(row.get("active"));
   jdbc.update("UPDATE notification.devices SET active=true,disabled_at=null,updated_at=CURRENT_TIMESTAMP,last_seen_at=CURRENT_TIMESTAMP WHERE id=?",id);
  }
  if(changed)audit.success("NOTIFICATION_DEVICE_REGISTERED","NOTIFICATION_DEVICE",id,null,Map.of("platform",platform));
  return devices(username).stream().filter(d->d.id().equals(id)).findFirst().orElseThrow();
 }
 @Transactional(readOnly=true) public List<DeviceView> devices(String username){
  return jdbc.query("SELECT id,platform,active,last_seen_at FROM notification.devices WHERE user_id=? ORDER BY created_at,id",
   (rs,i)->new DeviceView(rs.getObject("id",UUID.class),rs.getString("platform"),rs.getBoolean("active"),rs.getObject("last_seen_at",OffsetDateTime.class),"***"),owner(username));
 }
 @Transactional public void disable(String username,UUID id){
  UUID user=owner(username);
  var rows=jdbc.queryForList("SELECT active FROM notification.devices WHERE id=? AND user_id=? FOR UPDATE",id,user);
  if(rows.isEmpty())throw new ResourceNotFoundException("Device not found");
  if(Boolean.TRUE.equals(rows.getFirst().get("active"))){
   jdbc.update("UPDATE notification.devices SET active=false,disabled_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
   audit.success("NOTIFICATION_DEVICE_DISABLED","NOTIFICATION_DEVICE",id,null,Map.of("destinationType","DEVICE"));
  }
 }
 @Transactional(readOnly=true) public List<PreferenceView> preferences(String username){
  UUID user=owner(username);
  return List.of("PUSH","WHATSAPP","EMAIL").stream().map(c->new PreferenceView(c,Boolean.TRUE.equals(jdbc.query(
   "SELECT enabled FROM notification.preferences WHERE user_id=? AND channel=?",rs->rs.next()?rs.getBoolean(1):false,user,c)))).toList();
 }
 @Transactional public List<PreferenceView> preferences(String username,Map<String,Boolean> values){
  if(values==null||values.isEmpty()||values.entrySet().stream().anyMatch(e->!Set.of("PUSH","WHATSAPP","EMAIL").contains(e.getKey())||e.getValue()==null))
   throw new InvalidRequestException("Invalid notification preferences");
  UUID user=owner(username);
  jdbc.queryForList("SELECT id FROM identity.users WHERE id=? FOR UPDATE",user);
  for(var entry:new TreeMap<>(values).entrySet()){
   Boolean previous=jdbc.query("SELECT enabled FROM notification.preferences WHERE user_id=? AND channel=?",rs->rs.next()?rs.getBoolean(1):false,user,entry.getKey());
   jdbc.update("INSERT INTO notification.preferences(user_id,channel,enabled) VALUES (?,?,?) ON CONFLICT(user_id,channel) DO UPDATE SET enabled=EXCLUDED.enabled,updated_at=CURRENT_TIMESTAMP",user,entry.getKey(),entry.getValue());
   if(!entry.getValue().equals(previous))audit.success("NOTIFICATION_PREFERENCE_CHANGED","USER",user,null,Map.of("channel",entry.getKey(),"enabled",entry.getValue()));
  }
  return preferences(username);
 }
}
