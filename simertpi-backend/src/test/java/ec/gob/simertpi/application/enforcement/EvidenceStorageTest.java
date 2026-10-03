package ec.gob.simertpi.application.enforcement;

import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ForbiddenException;
import ec.gob.simertpi.application.audit.AuditService;
import ec.gob.simertpi.application.enforcement.storage.ObjectStorage;
import ec.gob.simertpi.domain.enforcement.entity.*;
import ec.gob.simertpi.domain.enforcement.repository.*;
import ec.gob.simertpi.domain.identity.entity.User;
import ec.gob.simertpi.infrastructure.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EvidenceStorageTest {
    @TempDir Path directory;
    EvidenceStorageProperties properties;
    LocalObjectStorage local;
    EvidenceRepository repository;
    ViolationRepository violations;
    InspectorAuthorizationService authorization;
    AuditService audit;
    EvidenceService service;
    UUID violationId;
    User inspector;
    static final byte[] JPEG = {(byte)255, (byte)216, (byte)255, 1};
    static final byte[] PNG = {(byte)137, 'P', 'N', 'G', 13, 10, 26, 10, 1};
    static final byte[] PDF = "%PDF-1.7\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    @BeforeEach void setup() {
        properties = new EvidenceStorageProperties(); properties.setBasePath(directory);
        local = new LocalObjectStorage(properties);
        repository = mock(EvidenceRepository.class); violations = mock(ViolationRepository.class);
        authorization = mock(InspectorAuthorizationService.class); audit = mock(AuditService.class);
        service = new EvidenceService(repository, violations, authorization, local, properties, audit);
        violationId = UUID.randomUUID(); inspector = new User(); inspector.setId(UUID.randomUUID());
        Violation violation = new Violation(); violation.setId(violationId); violation.setInspectorId(inspector.getId());
        when(authorization.requireInspector("inspector")).thenReturn(inspector);
        when(violations.findById(violationId)).thenReturn(Optional.of(violation));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }
    @AfterEach void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }
    EvidenceService.ValidatedFile jpeg() { return service.validateUpload("photo.jpg", "image/jpeg", JPEG); }
    Evidence upload(EvidenceService.ValidatedFile file) { return service.upload("inspector", violationId, file, null, null, null); }
    String key() { return "violations/" + violationId + "/" + jpeg().sha256() + ".jpg"; }
    void complete(int status) {
        for (var callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCompletion(status);
        TransactionSynchronizationManager.clearSynchronization();
    }
    @ParameterizedTest @CsvSource({"jpg,image/jpeg", "png,image/png", "pdf,application/pdf"})
    void supportsValidatedFormats(String extension, String mime) throws Exception {
        byte[] bytes = extension.equals("jpg") ? JPEG : extension.equals("png") ? PNG : PDF;
        var file = service.validateUpload("file." + extension, mime, bytes);
        Evidence saved = upload(file);
        assertThat(saved.getSha256Hash()).isEqualTo(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(saved.getViolationId()).isEqualTo(violationId);
        when(repository.findById(saved.getId())).thenReturn(Optional.of(saved));
        try (var object = service.download("inspector", saved.getId()).object()) {
            assertThat(object.stream().readAllBytes()).containsExactly(bytes);
            assertThat(object.contentType()).isEqualTo(mime);
        }
    }
    @Test void rejectsEmpty() { assertThatThrownBy(() -> service.validateUpload("f.jpg", "image/jpeg", new byte[0])).isInstanceOf(InvalidRequestException.class); }
    @Test void rejectsOversized() { properties.setMaxFileSizeBytes(3); assertThatThrownBy(this::jpeg).isInstanceOf(InvalidRequestException.class); }
    @Test void rejectsDeclaredMime() { assertThatThrownBy(() -> service.validateUpload("f.jpg", "text/plain", JPEG)).isInstanceOf(InvalidRequestException.class); }
    @Test void rejectsSpoofedMime() { assertThatThrownBy(() -> service.validateUpload("f.jpg", "image/jpeg", PDF)).isInstanceOf(InvalidRequestException.class); }
    @Test void rejectsExtension() { assertThatThrownBy(() -> service.validateUpload("f.exe", "image/jpeg", JPEG)).isInstanceOf(InvalidRequestException.class); }
    @Test void rejectsMismatchedExtension() { assertThatThrownBy(() -> service.validateUpload("f.png", "image/jpeg", JPEG)).isInstanceOf(InvalidRequestException.class); }
    @Test void sanitizesFilenameAndGeneratesKey() {
        var saved = upload(service.validateUpload("../../evil\\photo.jpg", "image/jpeg", JPEG));
        assertThat(saved.getOriginalFilename()).isEqualTo("photo.jpg");
        assertThat(saved.getStorageKey()).isEqualTo(key()).doesNotContain("photo");
    }
    @Test void doesNotTrustForgedValidatedFile() {
        var forged = new EvidenceService.ValidatedFile(JPEG, "file.jpg", "exe", "image/jpeg", "VIDEO", "a".repeat(64), 900);
        Evidence saved = upload(forged);
        assertThat(saved.getStorageKey()).isEqualTo(key()); assertThat(saved.getFileSize()).isEqualTo(JPEG.length);
        assertThat(saved.getEvidenceType()).isEqualTo("PHOTO");
    }
    @Test void defensiveCopyProtectsHash() {
        byte[] source = JPEG.clone(); var file = service.validateUpload("f.jpg", "image/jpeg", source);
        source[0] = 0; file.content()[0] = 0; assertThat(file.content()).containsExactly(JPEG);
    }
    @Test void disabledStorageFailsControlled() {
        properties.setEnabled(false); assertThatThrownBy(this::jpeg).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> local.read(key())).isInstanceOf(RuntimeException.class);
        verify(repository, never()).save(any());
    }
    @Test void storageFailureDoesNotSaveMetadata() throws Exception {
        ObjectStorage broken = mock(ObjectStorage.class); when(broken.putIfAbsent(any(), any(), any())).thenThrow(new IOException("failure"));
        service = new EvidenceService(repository, violations, authorization, broken, properties, audit);
        assertThatThrownBy(() -> upload(jpeg())).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any()); verifyNoInteractions(audit);
    }
    @Test void databaseFailureCompensatesWithoutSynchronization() throws Exception {
        when(repository.save(any())).thenThrow(new IllegalStateException("DB failure"));
        assertThatThrownBy(() -> upload(jpeg())).isInstanceOf(IllegalStateException.class);
        assertThat(local.exists(key())).isFalse();
    }
    @Test void rollbackCompensatesNewObjectAfterDeferredDatabaseFailure() throws Exception {
        TransactionSynchronizationManager.initSynchronization(); upload(jpeg());
        assertThat(local.exists(key())).isTrue(); complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(local.exists(key())).isFalse();
    }
    @Test void commitRetainsObject() throws Exception {
        TransactionSynchronizationManager.initSynchronization(); upload(jpeg()); complete(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(local.exists(key())).isTrue();
    }
    @Test void unknownTransactionOutcomeRetainsObjectForReconciliation() throws Exception {
        TransactionSynchronizationManager.initSynchronization(); upload(jpeg()); complete(TransactionSynchronization.STATUS_UNKNOWN);
        assertThat(local.exists(key())).isTrue();
    }
    @Test void rollbackNeverDeletesPreexistingObject() throws Exception {
        local.putIfAbsent(key(), JPEG, "image/jpeg"); TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(() -> upload(jpeg())).isInstanceOf(IllegalArgumentException.class);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK); assertThat(local.exists(key())).isTrue();
        verify(repository, never()).save(any());
    }
    @Test void duplicateDoesNotWriteOrAudit() throws Exception {
        Evidence saved = upload(jpeg()); when(repository.findByViolationIdAndStorageKeyAndSha256Hash(violationId, key(), jpeg().sha256())).thenReturn(Optional.of(saved));
        assertThat(upload(jpeg())).isSameAs(saved);
        try (var files = Files.walk(directory)) { assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1); }
        verify(repository, times(1)).save(any()); verify(audit, times(1)).success(eq("EVIDENCE_CREATED"), eq("EVIDENCE"), eq(saved.getId()), isNull(), any());
    }
    @Test void rollbackRetainsPreviouslyCommittedDuplicate() throws Exception {
        Evidence saved = upload(jpeg());
        when(repository.findByViolationIdAndStorageKeyAndSha256Hash(violationId, key(), jpeg().sha256())).thenReturn(Optional.of(saved));
        TransactionSynchronizationManager.initSynchronization(); assertThat(upload(jpeg())).isSameAs(saved);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK); assertThat(local.exists(key())).isTrue();
        verify(repository, times(1)).save(any());
    }
    @Test void rejectsHorizontalUpload() {
        Violation other = new Violation(); other.setInspectorId(UUID.randomUUID()); when(violations.findById(violationId)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> upload(jpeg())).isInstanceOf(ForbiddenException.class); verify(repository, never()).save(any());
    }
    @Test void rejectsHorizontalDownloadBeforeReadingStorage() {
        Evidence saved = upload(jpeg()); when(repository.findById(saved.getId())).thenReturn(Optional.of(saved));
        when(authorization.requireEvidenceAccess("other", inspector.getId())).thenThrow(new ForbiddenException());
        assertThatThrownBy(() -> service.download("other", saved.getId())).isInstanceOf(ForbiddenException.class);
    }
    @Test void rejectsCorruptContentBeforeReturningStream() throws Exception {
        Evidence saved = upload(jpeg()); when(repository.findById(saved.getId())).thenReturn(Optional.of(saved));
        Files.write(directory.resolve(key()), new byte[]{1,2,3,4});
        assertThatThrownBy(() -> service.download("inspector", saved.getId())).isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity");
    }
    @Test void rejectsOversizedRetrievedContent() throws Exception {
        Evidence saved = upload(jpeg()); when(repository.findById(saved.getId())).thenReturn(Optional.of(saved));
        properties.setMaxFileSizeBytes(4); Files.write(directory.resolve(key()), new byte[5]);
        assertThatThrownBy(() -> service.download("inspector", saved.getId())).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsTraversalOnAllStorageOperations() {
        for (String bad : List.of("../file.jpg", "violations/../../file.jpg", "C:\\file.jpg", "/tmp/file.jpg", key() + "/../file")) {
            assertThatThrownBy(() -> local.putIfAbsent(bad, JPEG, "image/jpeg")).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> local.read(bad)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> local.exists(bad)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> local.delete(bad)).isInstanceOf(InvalidRequestException.class);
        }
    }
    @Test void preventsOverwriteAndReturnsStreamWithoutPath() throws Exception {
        assertThat(local.putIfAbsent(key(), JPEG, "image/jpeg")).isTrue();
        assertThat(local.putIfAbsent(key(), PDF, "application/pdf")).isFalse();
        try (var object = local.read(key())) { assertThat(object.stream().readAllBytes()).containsExactly(JPEG); assertThat(object.toString()).doesNotContain(directory.toString()); }
    }
}
