package ec.gob.simertpi.application.identity.auth;

import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A device signature creates a new mobile session; it never restores logout tokens. */
@Service
public class DeviceCredentialService {
 private final JdbcTemplate jdbc;
 private final MobileAuthService sessions;
 private final SecureRandom random = new SecureRandom();
 public DeviceCredentialService(JdbcTemplate jdbc, MobileAuthService sessions) { this.jdbc=jdbc;this.sessions=sessions; }
 public record Challenge(UUID credentialId, UUID challengeId, String payload, OffsetDateTime expiresAt) {}
 private record Credential(UUID id, UUID owner, String key, boolean registered, boolean revoked) {}
 private record Proof(String purpose, String payload, boolean available) {}
 private static BadCredentialsException denied() { return new BadCredentialsException("Authentication required"); }
 static RSAPublicKey publicKey(String encoded) {
  try {
   var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
   if(key.getModulus().bitLength()!=2048 || !key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))) throw denied();
   return key;
  } catch(Exception error) { throw denied(); }
 }
 static boolean validSignature(String key, String payload, String encoded) {
  try { var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(publicKey(key));
   verifier.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));return verifier.verify(Base64.getDecoder().decode(encoded));
  } catch(Exception error) { return false; }
 }
 private Credential locked(UUID id) {
  var rows=jdbc.query("SELECT id,user_id,public_key,registered,revoked_at IS NOT NULL FROM identity.device_credentials WHERE id=? FOR UPDATE",
   (rs,n)->new Credential(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getBoolean(4),rs.getBoolean(5)),id);
  if(rows.isEmpty())throw denied();return rows.getFirst();
 }
 private UUID owner(String access) { return sessions.deviceOwner(access,false); }
 @Transactional
 public Challenge startRegistration(String access, UUID device, String key) {
  UUID owner=sessions.deviceOwner(access,true);publicKey(key);
  jdbc.queryForObject("SELECT id FROM identity.users WHERE id=? FOR UPDATE",UUID.class,owner);
  Long count=jdbc.queryForObject("SELECT count(*) FROM identity.device_credentials WHERE user_id=? AND created_at>CURRENT_TIMESTAMP-INTERVAL '1 minute'",Long.class,owner);
  if(count!=null && count>=5) throw denied();
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO identity.device_credentials(id,user_id,device_id,public_key) VALUES (?,?,?,?)",id,owner,device,key);
  return challenge(locked(id),"REGISTER");
 }
 private Challenge challenge(Credential credential, String purpose) {
  if(credential.revoked())throw denied();
  Long count=jdbc.queryForObject("SELECT count(*) FROM identity.device_challenges WHERE credential_id=? AND created_at>CURRENT_TIMESTAMP-INTERVAL '1 minute'",Long.class,credential.id());
  if(count!=null && count>=5)throw denied();
  byte[] nonce=new byte[32];random.nextBytes(nonce);UUID id=UUID.randomUUID();
  String payload="SIMERTPI-DEVICE-V1\n"+purpose+"\n"+credential.owner()+"\n"+credential.id()+"\n"+id+"\n"+Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
  OffsetDateTime expiry=OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2);
  jdbc.update("INSERT INTO identity.device_challenges(id,credential_id,purpose,payload,expires_at) VALUES (?,?,?,?,?)",id,credential.id(),purpose,payload,expiry);
  return new Challenge(credential.id(),id,payload,expiry);
 }
 @Transactional
 public Challenge loginChallenge(UUID id) { var c=locked(id);if(!c.registered())throw denied();return challenge(c,"LOGIN"); }
 @Transactional
 public Challenge unlockChallenge(String access, UUID id) {var c=locked(id);if(!c.owner().equals(owner(access))||!c.registered())throw denied();return challenge(c,"UNLOCK");}
 private Credential consume(UUID credentialId, UUID challengeId, String signature, String purpose, UUID owner) {
  var c=locked(credentialId);
  var proofs=jdbc.query("SELECT purpose,payload,consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP FROM identity.device_challenges WHERE id=? AND credential_id=? FOR UPDATE",
   (rs,n)->new Proof(rs.getString(1),rs.getString(2),rs.getBoolean(3)),challengeId,credentialId);
  if(proofs.isEmpty())throw denied();var proof=proofs.getFirst();
  // Consume even invalid attempts. AuthenticationException commits the one-use barrier.
  jdbc.update("UPDATE identity.device_challenges SET consumed_at=COALESCE(consumed_at,CURRENT_TIMESTAMP) WHERE id=?",challengeId);
  if(c.revoked()||!proof.available()||!proof.purpose().equals(purpose)||(owner!=null&&!c.owner().equals(owner))||
    (!purpose.equals("REGISTER")&&!c.registered())||!validSignature(c.key(),proof.payload(),signature))throw denied();
  return c;
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public void finishRegistration(String access, UUID id, UUID challenge, String signature) {
  var c=consume(id,challenge,signature,"REGISTER",sessions.deviceOwner(access,true));
  jdbc.update("UPDATE identity.device_credentials SET registered=true WHERE id=?",c.id());
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public MobileAuthService.SessionResponse authenticate(UUID id, UUID challenge, String signature) {
  var c=consume(id,challenge,signature,"LOGIN",null);return sessions.issueDeviceSession(c.owner());
 }
 @Transactional(noRollbackFor=AuthenticationException.class)
 public void unlock(String access, UUID id, UUID challenge, String signature) { consume(id,challenge,signature,"UNLOCK",owner(access)); }
 @Transactional
 public void revoke(String access, UUID id) {
  var c=locked(id);if(!c.owner().equals(owner(access)))throw denied();
  jdbc.update("UPDATE identity.device_credentials SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE id=?",id);
 }
}
