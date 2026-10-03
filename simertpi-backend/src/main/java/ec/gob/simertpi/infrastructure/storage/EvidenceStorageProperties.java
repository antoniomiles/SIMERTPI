package ec.gob.simertpi.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Set;

@ConfigurationProperties(prefix = "simertpi.evidence.storage")
public class EvidenceStorageProperties {
    private boolean enabled = true;
    private Path basePath = Path.of(System.getProperty("java.io.tmpdir"), "simertpi-evidence");
    private long maxFileSizeBytes = 10 * 1024 * 1024;
    private Set<String> allowedMediaTypes = Set.of("image/jpeg", "image/png", "application/pdf");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getBasePath() { return basePath; }
    public void setBasePath(Path basePath) { this.basePath = basePath; }
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
    public Set<String> getAllowedMediaTypes() { return allowedMediaTypes; }
    public void setAllowedMediaTypes(Set<String> allowedMediaTypes) { this.allowedMediaTypes = Set.copyOf(allowedMediaTypes); }
}
