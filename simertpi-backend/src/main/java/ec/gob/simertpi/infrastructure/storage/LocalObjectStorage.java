package ec.gob.simertpi.infrastructure.storage;

import ec.gob.simertpi.application.enforcement.storage.ObjectStorage;
import ec.gob.simertpi.api.InvalidRequestException;
import ec.gob.simertpi.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

@Component
public class LocalObjectStorage implements ObjectStorage {
    private static final Pattern KEY_PATTERN = Pattern.compile("violations/[0-9a-fA-F-]{36}/[0-9a-f]{64}\\.(jpg|png|pdf)");
    private final EvidenceStorageProperties properties;
    private final Path basePath;

    public LocalObjectStorage(EvidenceStorageProperties properties) {
        this.properties = properties;
        this.basePath = properties.getBasePath().toAbsolutePath().normalize();
    }

    @Override
    public boolean putIfAbsent(String key, byte[] content, String contentType) throws IOException {
        Path path = resolve(key);
        Files.createDirectories(path.getParent());
        rejectSymbolicLinks(path);
        java.io.OutputStream output;
        try {
            output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            return false;
        }
        try (output) {
            output.write(content);
            return true;
        } catch (IOException failure) {
            try { Files.deleteIfExists(path); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override
    public StoredObject read(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResourceNotFoundException("Evidence content not found");
        }
        String mediaType = mediaTypeFor(path);
        return new StoredObject(Files.newInputStream(path), Files.size(path), mediaType);
    }

    @Override
    public boolean exists(String key) throws IOException {
        return Files.isRegularFile(resolve(key), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        if (!properties.isEnabled()) throw new IllegalStateException("Evidence storage is disabled");
        if (key == null || !KEY_PATTERN.matcher(key).matches()) throw new InvalidRequestException("Invalid storage key");
        Path resolved = basePath.resolve(key).normalize();
        if (!resolved.startsWith(basePath)) throw new InvalidRequestException("Invalid storage key");
        rejectSymbolicLinks(resolved);
        return resolved;
    }

    private void rejectSymbolicLinks(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new InvalidRequestException("Invalid storage location");
        }
    }

    private String mediaTypeFor(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        return "application/pdf";
    }
}
