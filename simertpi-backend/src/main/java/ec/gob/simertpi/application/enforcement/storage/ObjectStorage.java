package ec.gob.simertpi.application.enforcement.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ObjectStorage {
    boolean putIfAbsent(String key, byte[] content, String contentType) throws IOException;
    /** Links content written in the current transaction to its metadata row. */
    default void associateEvidence(String key, java.util.UUID evidenceId) throws IOException { }
    /** True when content mutations participate in the caller's database transaction. */
    default boolean usesCallerTransaction() { return false; }
    StoredObject read(String key) throws IOException;
    boolean exists(String key) throws IOException;
    void delete(String key) throws IOException;
    /** Provider-neutral inventory; keys are internal and must never enter public DTOs. */
    default java.util.List<String> listKeys() throws IOException {
        throw new UnsupportedOperationException("Storage inventory not supported");
    }

    record StoredObject(InputStream stream, long size, String contentType) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
