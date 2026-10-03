package ec.gob.simertpi.application.enforcement.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ObjectStorage {
    boolean putIfAbsent(String key, byte[] content, String contentType) throws IOException;
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
