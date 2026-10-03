package ec.gob.simertpi.application.reconciliation;
import ec.gob.simertpi.application.enforcement.storage.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Service
public class EvidenceReconciliationService {
 private final JdbcTemplate jdbc;private final ObjectStorage storage;private final TransactionTemplate tx;private final ReconciliationFindings findings;
 public EvidenceReconciliationService(JdbcTemplate jdbc,ObjectStorage storage,PlatformTransactionManager manager,ReconciliationFindings findings) {
  this.jdbc=jdbc;this.storage=storage;this.tx=new TransactionTemplate(manager);this.findings=findings;
 }
 public List<ReconciliationResult> reconcile() {
  List<ReconciliationResult> results=new ArrayList<>();
  try {
   for(UUID id:jdbc.queryForList("SELECT id FROM enforcement.evidence ORDER BY id",UUID.class)) {
    var item=tx.execute(s -> inspectMetadata(id));if(item!=null)results.add(item);
   }
   for(String key:storage.listKeys()) {
    var item=tx.execute(s -> inspectOrphan(key));if(item!=null)results.add(item);
   }
  }catch(IOException failure) {throw new IllegalStateException("Evidence inventory unavailable");}
  return results;
 }
 private ReconciliationResult inspectMetadata(UUID id) {
  UUID violation=jdbc.query("SELECT violation_id FROM enforcement.evidence WHERE id=?",rs -> rs.next()?rs.getObject(1,UUID.class):null,id);
  if(violation==null)return null;
  lockViolation(violation);
  var rows=jdbc.queryForList("SELECT * FROM enforcement.evidence WHERE id=?",id);
  if(rows.isEmpty())return null;
  var metadata=rows.getFirst();String key=(String)metadata.get("storage_key");String issue="CONSISTENT";
  try {
   if(!storage.exists(key)) issue="METADATA_WITHOUT_OBJECT";
   else try(var object=storage.read(key)) {
    var digest=MessageDigest.getInstance("SHA-256");long size=0;byte[] buffer=new byte[8192];int read;
    while((read=object.stream().read(buffer))!=-1) {digest.update(buffer,0,read);size+=read;}
    if(metadata.get("file_size")==null || size!=((Number)metadata.get("file_size")).longValue())issue="SIZE_MISMATCH";
    else if(!HexFormat.of().formatHex(digest.digest()).equals(metadata.get("sha256_hash")))issue="HASH_MISMATCH";
   }
  }catch(ec.gob.simertpi.api.InvalidRequestException invalidReference) {issue="INVALID_STORAGE_REFERENCE";}
  catch(IOException | ec.gob.simertpi.api.ResourceNotFoundException unavailable) {issue="STORAGE_VERIFICATION_UNAVAILABLE";}
  catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException("SHA-256 unavailable");}
  return findings.report("EVIDENCE",id,issue,"CONSISTENT".equals(issue)?"CONSISTENT":"MANUAL_REVIEW_REQUIRED","NONE","EVIDENCE_INCONSISTENCY_DETECTED");
 }
 private ReconciliationResult inspectOrphan(String key) {
  // CP9 upload holds this same parent lock until metadata commits or compensation finishes.
  String[] parts=key.split("/");
  if(parts.length==3 && "violations".equals(parts[0])) {
   try {lockViolation(UUID.fromString(parts[1]));}catch(IllegalArgumentException invalid) { /* Unmanaged object: diagnosis only. */ }
  }
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enforcement.evidence WHERE storage_key=?)",Boolean.class,key)))return null;
  try {if(!storage.exists(key))return null;}catch(IOException unavailable) {throw new IllegalStateException("Evidence inventory unavailable");}
  UUID opaqueId=UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
  return findings.report("EVIDENCE_OBJECT",opaqueId,"OBJECT_WITHOUT_METADATA","MANUAL_REVIEW_REQUIRED","NONE","EVIDENCE_INCONSISTENCY_DETECTED");
 }
 private void lockViolation(UUID id) {jdbc.queryForList("SELECT id FROM enforcement.violations WHERE id=? FOR UPDATE",id);}
}
